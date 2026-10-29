package com.kakario.comicviewer.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.kakario.comicviewer.ftp.FtpBrowser
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.RangeSource
import com.kakario.comicviewer.model.UserFacingException
import com.kakario.comicviewer.thumb.ThumbOrigin
import com.kakario.comicviewer.thumb.ZipCover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * 열어 놓은 만화책(ZIP) 하나.
 * ZIP을 통째로 받지 않고, 볼 페이지(이미지)만 그때그때 읽어서 보여준다.
 * FTP에서는 이 책 전용 연결을 따로 열어서 책장/표지 로딩과 부딪히지 않게 한다.
 */
class ComicBook(
    private val origin: ThumbOrigin,
    val entry: FileEntry,
    /** 화면에 맞춰 줄일 최대 크기(px). 너무 큰 원본으로 메모리가 터지는 것을 막는다. */
    private val maxSide: Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ftp: FtpBrowser? = origin.server?.let { FtpBrowser(it) }
    private val localLock = Any()
    private var localFile: RandomAccessFile? = null

    private var pages: List<ZipCover.Entry> = emptyList()
    val pageCount: Int get() = pages.size

    private val cache = object : LruCache<Int, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 5).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }
    private val inFlight = ConcurrentHashMap<Int, Deferred<Bitmap>>()

    /** 목차를 읽어 페이지 목록을 만든다. 페이지 수를 돌려준다. */
    suspend fun open(): Int {
        val entries = withSource { ZipCover.listEntries(it) }
            ?: throw UserFacingException("ZIP 파일을 읽을 수 없어요.\n파일이 손상됐거나 ZIP 형식이 아닐 수 있어요.")
        pages = ZipCover.imagePages(entries)
        if (pages.isEmpty()) throw UserFacingException("이 ZIP 안에는 볼 수 있는 이미지가 없어요.")
        return pages.size
    }

    fun cached(index: Int): Bitmap? = cache.get(index)

    /** 페이지 이미지. 같은 페이지를 여러 곳에서 동시에 요청해도 한 번만 읽는다. */
    suspend fun page(index: Int): Bitmap {
        cache.get(index)?.let { return it }
        val job = inFlight.getOrPut(index) { scope.async { load(index) } }
        return job.await()
    }

    /** 다음에 볼 페이지를 미리 읽어둔다 (실패해도 조용히 넘어감). */
    fun prefetch(index: Int) {
        if (index !in pages.indices || cache.get(index) != null || inFlight.containsKey(index)) return
        scope.launch { runCatching { page(index) } }
    }

    private suspend fun load(index: Int): Bitmap {
        try {
            val e = pages.getOrNull(index) ?: throw UserFacingException("없는 페이지예요")
            val bytes = withSource { ZipCover.extract(it, e) }
                ?: throw UserFacingException("이 페이지를 읽을 수 없어요 (지원하지 않는 압축 방식)")
            val bmp = withContext(Dispatchers.Default) { decode(bytes) }
                ?: throw UserFacingException("이미지를 표시할 수 없어요")
            cache.put(index, bmp)
            return bmp
        } finally {
            inFlight.remove(index)
        }
    }

    private suspend fun <T> withSource(block: (RangeSource) -> T): T {
        val server = ftp
        return if (server != null) {
            server.useFile(entry.path, entry.size, block)
        } else {
            withContext(Dispatchers.IO) {
                synchronized(localLock) {
                    val raf = localFile ?: RandomAccessFile(entry.path, "r").also { localFile = it }
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

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    fun close() {
        scope.cancel()
        val conn = ftp
        val file = localFile
        localFile = null
        Thread {
            runCatching { file?.close() }
            if (conn != null) kotlinx.coroutines.runBlocking { runCatching { conn.close() } }
        }.start()
        cache.evictAll()
    }
}
