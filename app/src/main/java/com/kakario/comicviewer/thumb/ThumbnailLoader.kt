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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
class ThumbnailLoader private constructor(context: Context) {
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
            // 폴더에는 표지를 만들지 않는다 (파일만)
            val raw = if (entry.isDirectory) null else zipCover(origin, entry)
            raw?.let { withContext(Dispatchers.Default) { makeThumb(it) } }
                ?.also { writeDisk(key, it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 접속 오류 등 — 이번엔 실패로 두고, 다음에 화면을 다시 열면 재시도
            return null
        }
        if (bmp == null) {
            misses += key
            // 파일은 읽었는데 표지가 없음(2중 압축·손상 등) → 기억해서 폴더 들어갈 때마다 다시 안 받음
            if (!entry.isDirectory) markFailed(key)
        } else {
            memory.put(key, bmp)
        }
        return bmp
    }

    // ───── 표지를 못 만든 파일 기억 (앱을 껐다 켜도 유지)
    private val failedFile = File(dir, "failed-v2.txt").also { File(dir, "failed.txt").delete() } // v2: AVIF 지원 후 예전 실패 목록 다시 시도
    private val failed: MutableSet<String> by lazy {
        ConcurrentHashMap.newKeySet<String>().apply {
            runCatching { failedFile.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.let { addAll(it) } }
        }
    }

    private fun markFailed(key: String) {
        if (failed.add(key)) runCatching { failedFile.appendText(key + "\n") }
    }

    private fun saveFailed() {
        runCatching { failedFile.writeText(failed.joinToString("\n", postfix = "\n")) }
    }

    /** 이 파일 표지가 이미 있는지 (메모리·디스크). */
    fun hasThumb(origin: ThumbOrigin, entry: FileEntry): Boolean {
        val key = keyFor(origin, entry)
        return memory.get(key) != null || fileFor(key).exists()
    }

    /** 전에 표지를 못 만든 파일인지. */
    fun isFailed(origin: ThumbOrigin, entry: FileEntry): Boolean = keyFor(origin, entry) in failed

    // ───────────────────────── 폴더 썸네일 갱신 작업 줄 ─────────────────────────
    //  · 한 번에 한 폴더만 처리한다.
    //  · '지금 보는 폴더'는 자동으로 갱신하고, 다른 폴더로 가면 그 작업은 취소된다.
    //  · 길게 눌러 '썸네일 갱신하기'를 누른 폴더들은 줄을 서서 차례로 처리된다.
    //  · 이미 있는 표지는 건너뛰고, 없는 표지만 만든다.

    private class Job(
        val id: Long,
        val origin: ThumbOrigin,
        val path: String,
        val label: String,
        val auto: Boolean,
        /** 화면이 이미 읽어둔 폴더 목록 (있으면 다시 안 읽음) */
        val entries: List<FileEntry>? = null,
    ) {
        @Volatile var done: Int = 0
        /** 폴더 안 ZIP 수. 아직 모르면 null. */
        @Volatile var total: Int? = null
        fun same(o: ThumbOrigin, p: String) = origin == o && path == p
    }

    /** 화면에 보여줄 작업 하나: 폴더 이름, 처리한 수 / 전체 수. */
    data class JobInfo(val id: Long, val label: String, val done: Int, val total: Int?, val auto: Boolean)

    /** 화면 상단·상세 창에 보여줄 상태. null이면 아무 작업도 없음. */
    data class Status(val current: JobInfo, val waiting: List<JobInfo>, val phase: String = "썸네일 갱신 중")

    /** 지금 단계 (썸네일 갱신 → 중복 확인 → 압축 오류 확인) */
    @Volatile private var phase = "썸네일 갱신 중"

    fun setPhase(p: String) {
        phase = p
        synchronized(jobLock) { publishLocked() }
    }

    /**
     * 한 폴더 썸네일 갱신이 끝난 뒤 이어서 할 일 (중복 확인 → 압축 오류 확인).
     * 같은 작업 줄 안에서 차례로 돌기 때문에 다른 갱신과 부딪히지 않는다.
     * failed = 표지를 못 만든 파일들.
     */
    var onFolderDone: (suspend (origin: ThumbOrigin, path: String, label: String, zips: List<FileEntry>, failed: List<FileEntry>) -> Unit)? = null

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status

    private val jobLock = Any()
    private var nextId = 1L
    private var autoJob: Job? = null
    private val queue = ArrayDeque<Job>()
    private var running: Job? = null
    private var runningTask: kotlinx.coroutines.Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { worker() }
    }

    /**
     * 지금 보고 있는 폴더 — 이전 폴더의 자동 갱신은 멈추고 이 폴더로 바꾼다.
     * zipCount를 알면 넘겨서 '0/100'처럼 바로 보여준다.
     */
    fun watchFolder(origin: ThumbOrigin, path: String, label: String, entries: List<FileEntry>? = null) {
        synchronized(jobLock) {
            val r = running
            if (r != null && r.auto && r.same(origin, path)) return
            if (r != null && r.auto) runningTask?.cancel()
            autoJob = Job(nextId++, origin, path, label, auto = true, entries = entries)
                .also { it.total = entries?.count { e -> e.isComicArchive } }
            publishLocked()
        }
        wake.trySend(Unit)
    }

    /** 폴더를 떠날 때 — 그 폴더의 자동 갱신을 멈춘다. */
    fun unwatchFolder(origin: ThumbOrigin, path: String) {
        synchronized(jobLock) {
            if (autoJob?.same(origin, path) == true) autoJob = null
            val r = running
            if (r != null && r.auto && r.same(origin, path)) runningTask?.cancel()
            publishLocked()
        }
    }

    /** 길게 눌러 요청한 폴더 갱신 — 줄 맨 뒤에 선다. 이미 줄에 있으면 false. */
    fun requestFolder(origin: ThumbOrigin, path: String, label: String): Boolean {
        val job: Job
        synchronized(jobLock) {
            val r = running
            if (r != null && !r.auto && r.same(origin, path)) return false
            if (queue.any { it.same(origin, path) }) return false
            job = Job(nextId++, origin, path, label, auto = false)
            queue.addLast(job)
            publishLocked()
        }
        // 대기 목록에 '0/100'처럼 보이도록 ZIP 수를 미리 세어둔다
        scope.launch {
            runCatching { job.total = list(origin, path).count { it.isComicArchive } }
            synchronized(jobLock) { publishLocked() }
        }
        wake.trySend(Unit)
        return true
    }

    /** 대기 중인 작업 하나를 뺀다. */
    fun removeWaiting(id: Long) {
        synchronized(jobLock) {
            if (autoJob?.id == id) autoJob = null
            queue.removeAll { it.id == id }
            publishLocked()
        }
    }

    /** 지금 진행 중인 작업만 멈춘다 (다음 대기 작업으로 넘어감). */
    fun skipCurrent() {
        synchronized(jobLock) { runningTask?.cancel() }
    }

    /** 진행 중·대기 중인 갱신을 모두 멈춘다. */
    fun stopAll() {
        synchronized(jobLock) {
            queue.clear()
            autoJob = null
            runningTask?.cancel()
            publishLocked()
        }
    }

    private fun Job.info() = JobInfo(id, label, done, total, auto)

    private fun publishLocked() {
        val r = running
        if (r == null) {
            _status.value = null
            return
        }
        val waiting = buildList {
            autoJob?.let { add(it.info()) }
            queue.forEach { add(it.info()) }
        }
        _status.value = Status(r.info(), waiting, phase)
    }

    private suspend fun worker() {
        while (true) {
            val job = synchronized(jobLock) {
                // 지금 보는 폴더가 먼저, 그다음 요청한 폴더 순서대로
                val next = autoJob?.also { autoJob = null } ?: queue.removeFirstOrNull()
                running = next
                // 지금 보는 폴더 자동 갱신은 할 게 있을 때만 표시 (process가 확인 후 띄움)
                if (next == null || !next.auto) publishLocked()
                next
            }
            if (job == null) {
                wake.receive()
                continue
            }
            phase = "썸네일 갱신 중"
            val task = scope.launch { process(job) }
            synchronized(jobLock) { runningTask = task }
            task.join()
            synchronized(jobLock) {
                running = null
                runningTask = null
                publishLocked()
            }
        }
    }

    private suspend fun process(job: Job) {
        val zips = try {
            (job.entries ?: list(job.origin, job.path)).filter { it.isComicArchive }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return // 폴더를 못 읽으면 이 작업은 건너뛴다
        }
        // 이미 표지가 있거나, 전에 확실히 못 만든 파일은 건너뜀 → 할 게 없으면 표시 없이 바로 끝
        val needed = withContext(Dispatchers.IO) {
            zips.filter { !hasThumb(job.origin, it) && !isFailed(job.origin, it) }
        }
        if (needed.isEmpty() && job.auto) {
            synchronized(jobLock) { running = null; publishLocked() }
            return
        }
        job.total = needed.size
        job.done = 0
        synchronized(jobLock) { publishLocked() }
        for (e in needed) {
            paused.first { !it } // 만화 보는 중엔 잠깐 쉼
            misses.remove(keyFor(job.origin, e))
            load(job.origin, e)
            job.done++
            synchronized(jobLock) { publishLocked() }
        }
        // 썸네일이 끝나면 이어서 중복 확인 → 압축 오류 확인 (차례로)
        val failedNow = zips.filter { isFailed(job.origin, it) }
        runCatching { onFolderDone?.invoke(job.origin, job.path, job.label, zips, failedNow) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    /** 주어진 항목들의 표지를 지우고 다시 만들게 한다. */
    fun invalidate(origin: ThumbOrigin, entries: List<FileEntry>) {
        entries.forEach { e ->
            val key = keyFor(origin, e)
            memory.remove(key)
            misses.remove(key)
            failed.remove(key)
            fileFor(key).delete()
        }
        saveFailed()
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
        val decoded = com.kakario.comicviewer.viewer.ImageCodec.decode(raw, TARGET_WIDTH * 2, Bitmap.Config.RGB_565)
            ?: return null

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
        failed.clear()
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

    companion object {
        private const val TARGET_WIDTH = 360

        @Volatile private var instance: ThumbnailLoader? = null

        /** 앱 전체에서 하나만 쓴다 (화면과 백그라운드 서비스가 같은 작업 줄을 공유). */
        fun get(context: Context): ThumbnailLoader =
            instance ?: synchronized(this) {
                instance ?: ThumbnailLoader(context.applicationContext).also { instance = it }
            }
    }
}
