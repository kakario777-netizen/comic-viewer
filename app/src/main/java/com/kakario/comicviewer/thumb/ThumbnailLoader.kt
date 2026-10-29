package com.kakario.comicviewer.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.kakario.comicviewer.data.FtpServer
import com.kakario.comicviewer.ftp.FtpBrowser
import com.kakario.comicviewer.local.LocalStorage
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.RangeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 썸네일을 어디서 읽어올지: FTP 서버 또는 내 폰(null). */
data class ThumbOrigin(val server: FtpServer?) {
    val tag: String get() = server?.let { "ftp${it.id}" } ?: "local"
}

/**
 * 표지 썸네일을 만들고 기억해두는 곳.
 * - 메모리(빠름) → 디스크 캐시(앱을 다시 켜도 유지) → 실제로 ZIP에서 추출 순서로 찾는다.
 * - 같은 표지를 화면과 '폴더 전체 미리 만들기'가 동시에 요청해도 한 번만 만든다.
 * - FTP는 화면 탐색·만화 보기와 부딪히지 않도록 서버마다 썸네일 전용 연결을 따로 쓴다.
 */
class ThumbnailLoader(context: Context) {
    private val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 표지를 못 찾은 항목 — 다시 시도하지 않는다 (앱을 켜 있는 동안). */
    private val misses = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    /** ZIP 안 이미지 수 (표지 만들 때 함께 알아둔다). */
    private val pageCounts = ConcurrentHashMap<String, Int>()

    private val ftpConnections = ConcurrentHashMap<FtpServer, FtpBrowser>()
    private val localGate = Semaphore(3)

    /** 다시 만들기를 하면 올라가는 번호 — 화면의 표지들이 새로 그려지게 하는 신호. */
    var generation by mutableIntStateOf(0)
        private set

    /** 만화를 보는 동안에는 폴더 전체 미리 만들기를 잠시 멈춘다 (보는 쪽에 속도 양보). */
    val paused = MutableStateFlow(false)

    fun cached(key: String): Bitmap? = memory.get(key)

    fun keyFor(origin: ThumbOrigin, entry: FileEntry): String =
        if (entry.isDirectory) "${origin.tag}:dir:${entry.path}"
        else "${origin.tag}:zip:${entry.path}:${entry.size}:${entry.modified}"

    /** 폴더면 그 안의 첫 권 표지, ZIP이면 자기 표지. 없으면 null. */
    suspend fun load(origin: ThumbOrigin, entry: FileEntry): Bitmap? {
        val key = keyFor(origin, entry)
        memory.get(key)?.let { return it }
        if (key in misses) return null
        val job = inFlight.getOrPut(key) {
            scope.async {
                try {
                    produce(key, origin, entry)
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return job.await()
    }

    private suspend fun produce(key: String, origin: ThumbOrigin, entry: FileEntry): Bitmap? {
        val bmp = readDisk(key) ?: try {
            val raw = if (entry.isDirectory) folderCover(origin, entry.path) else zipCover(origin, entry)
            raw?.let { withContext(Dispatchers.Default) { makeThumb(it) } }
                ?.also { writeDisk(key, it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 접속 오류 등 — 이번엔 실패로 두고, 다음에 화면을 다시 열면 재시도
            return null
        }
        if (bmp == null) misses += key else memory.put(key, bmp)
        return bmp
    }

    /**
     * 폴더 안 표지를 화면에 안 보이는 것까지 차례로 만들어둔다.
     * onProgress(만든 개수, 전체). 호출한 쪽이 취소하면(폴더를 나가면) 멈춘다.
     */
    suspend fun preload(origin: ThumbOrigin, entries: List<FileEntry>, onProgress: (Int, Int) -> Unit) {
        val targets = entries.filter { it.isDirectory || it.isComicArchive }
        onProgress(0, targets.size)
        targets.forEachIndexed { i, e ->
            paused.first { !it }
            load(origin, e)
            onProgress(i + 1, targets.size)
        }
    }

    /** 주어진 항목들의 표지를 지우고 다시 만들게 한다. */
    fun invalidate(origin: ThumbOrigin, entries: List<FileEntry>) {
        entries.forEach { e ->
            val key = keyFor(origin, e)
            memory.remove(key)
            misses.remove(key)
            fileFor(key).delete()
        }
        generation++
    }

    /** ZIP 안 이미지 수. 모르면 목차만 읽어서 알아낸다 (파일 전체를 받지 않음). */
    suspend fun pageCount(origin: ThumbOrigin, entry: FileEntry): Int? {
        val key = keyFor(origin, entry)
        pageCounts[key]?.let { return it }
        val count = withSource(origin, entry) { src ->
            ZipCover.listEntries(src)?.let { ZipCover.imagePages(it).size }
        } ?: return null
        pageCounts[key] = count
        return count
    }

    /** 폴더 표지: 이름순 첫 ZIP, 없으면 첫 하위 폴더 안의 첫 ZIP. */
    private suspend fun folderCover(origin: ThumbOrigin, path: String, depth: Int = 0): ByteArray? {
        val entries = list(origin, path)
        entries.firstOrNull { it.isComicArchive }?.let { return zipCover(origin, it) }
        if (depth >= 1) return null
        val sub = entries.firstOrNull { it.isDirectory } ?: return null
        return folderCover(origin, sub.path, depth + 1)
    }

    suspend fun list(origin: ThumbOrigin, path: String): List<FileEntry> {
        val server = origin.server
        return if (server == null) {
            localGate.withPermit { LocalStorage.list(path) }
        } else {
            ftp(server).list(path)
        }
    }

    private suspend fun zipCover(origin: ThumbOrigin, entry: FileEntry): ByteArray? =
        withSource(origin, entry) { src ->
            val entries = ZipCover.listEntries(src) ?: return@withSource null
            val pages = ZipCover.imagePages(entries)
            pageCounts[keyFor(origin, entry)] = pages.size
            pages.firstOrNull()?.let { ZipCover.extract(src, it) }
        }

    private suspend fun <T> withSource(origin: ThumbOrigin, entry: FileEntry, block: (RangeSource) -> T): T {
        val server = origin.server
        if (server != null) return ftp(server).useFile(entry.path, entry.size, block)
        return localGate.withPermit {
            withContext(Dispatchers.IO) {
                RandomAccessFile(entry.path, "r").use { raf ->
                    block(object : RangeSource {
                        override val size: Long = raf.length()
                        override fun read(offset: Long, length: Int): ByteArray {
                            val n = minOf(length.toLong(), size - offset).coerceAtLeast(0).toInt()
                            val buf = ByteArray(n)
                            raf.seek(offset)
                            raf.readFully(buf)
                            return buf
                        }
                    })
                }
            }
        }
    }

    private fun ftp(server: FtpServer): FtpBrowser =
        ftpConnections.getOrPut(server) { FtpBrowser(server) }

    /** 원본 이미지를 책장 크기(가로 약 360px)로 줄인다. 메모리를 아끼려 RGB565로. */
    private fun makeThumb(raw: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            raw, 0, raw.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        ) ?: return null

        if (decoded.width <= TARGET_WIDTH) return decoded
        val h = (decoded.height.toLong() * TARGET_WIDTH / decoded.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, TARGET_WIDTH, h, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun fileFor(key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.jpg")
    }

    private fun readDisk(key: String): Bitmap? {
        val f = fileFor(key)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 },
        )
    }

    private fun writeDisk(key: String, bmp: Bitmap) {
        try {
            fileFor(key).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (_: Exception) {
        }
    }

    /** 설정 화면에서 쓰는 캐시 정보/삭제. */
    fun diskCacheBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        memory.evictAll()
        misses.clear()
        dir.listFiles()?.forEach { it.delete() }
        generation++
    }

    /** 앱이 끝날 때 썸네일 전용 FTP 연결을 모두 닫는다. */
    fun shutdown() {
        val conns = ftpConnections.values.toList()
        ftpConnections.clear()
        Thread {
            runBlocking { conns.forEach { runCatching { it.close() } } }
        }.start()
    }

    private companion object {
        const val TARGET_WIDTH = 360
    }
}
