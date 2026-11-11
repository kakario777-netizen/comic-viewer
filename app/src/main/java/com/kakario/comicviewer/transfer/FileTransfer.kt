package com.kakario.comicviewer.transfer

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import com.kakario.comicviewer.data.FtpServer
import com.kakario.comicviewer.ftp.FtpBrowser
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.UserFacingException
import com.kakario.comicviewer.model.joinPath
import com.kakario.comicviewer.thumb.ThumbOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * 파일 복사·이동 (내 폰 ↔ FTP, 폰 안, 서버 안, 서버끼리) + 이름 바꾸기.
 * 고른 파일을 '클립보드'에 담아두고, 옮길 폴더에서 '여기에 붙여넣기'.
 */
class FileTransfer private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Clip(val origin: ThumbOrigin, val fromPath: String, val items: List<FileEntry>, val move: Boolean)

    sealed interface State {
        data object Idle : State
        data class Working(
            val title: String,
            val fileIndex: Int,
            val fileCount: Int,
            val name: String,
            val bytesDone: Long,
            val bytesTotal: Long,
        ) : State
        data class Done(val message: String) : State
    }

    private val _clip = MutableStateFlow<Clip?>(null)
    val clip: StateFlow<Clip?> = _clip

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    private val readConns = ConcurrentHashMap<FtpServer, FtpBrowser>()
    private val writeConns = ConcurrentHashMap<FtpServer, FtpBrowser>()

    fun setClip(c: Clip?) {
        _clip.value = c
    }

    fun cancel() {
        job?.cancel()
    }

    fun clearDone() {
        if (_state.value is State.Done) _state.value = State.Idle
    }

    // ───────────────────────── 이름 바꾸기

    /** 같은 폴더 안에서 이름만 바꾼다. 바뀐 새 경로를 돌려줌. */
    suspend fun rename(origin: ThumbOrigin, entry: FileEntry, newName: String): String {
        val name = cleanName(newName)
        if (name.isEmpty()) throw UserFacingException("이름을 입력해 주세요")
        val parent = entry.path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        val to = joinPath(parent, name)
        if (to == entry.path) return to
        val taken = list(origin, parent, hidden = true).map { it.name }
        if (name in taken) throw UserFacingException("같은 이름이 이미 있어요")
        moveSame(origin, entry.path, to)
        return to
    }

    // ───────────────────────── 붙여넣기

    fun paste(dest: ThumbOrigin, destPath: String) {
        val c = _clip.value ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            val title = if (c.move) "옮기는 중" else "복사하는 중"
            val locks = acquireLocks()
            var ok = 0
            val failed = mutableListOf<String>()
            try {
                // 먼저 전체 크기·개수 세기 (폴더는 안까지)
                _state.value = State.Working("준비 중", 0, c.items.size, "파일 목록 세는 중", 0, 0)
                val plan = c.items.map { it to (if (it.isDirectory) runCatching { measure(c.origin, it.path) }.getOrDefault(0 to 0L) else 1 to it.size) }
                val fileCount = plan.sumOf { it.second.first }.coerceAtLeast(1)
                val bytesTotal = plan.sumOf { it.second.second }
                val progress = Progress(title, fileCount, bytesTotal)
                for ((item, _) in plan) {
                    coroutineContext.ensureActive()
                    try {
                        val dst = destPath.trimEnd('/').ifEmpty { "/" }
                        if (sameOrigin(c.origin, dest) && item.isDirectory &&
                            (dst == item.path.trimEnd('/') || dst.startsWith(item.path.trimEnd('/') + "/"))
                        ) throw UserFacingException("폴더를 자기 안으로 넣을 수 없어요")
                        val taken = list(dest, dst, hidden = true).map { it.name }.toSet()
                        val sameFolder = sameOrigin(c.origin, dest) && c.fromPath.trimEnd('/') == dst
                        if (c.move && sameFolder) { ok++; continue } // 같은 자리로 옮기기 = 그대로
                        val name = uniqueName(item.name, taken)
                        val target = joinPath(dst, name)
                        if (c.move && sameOrigin(c.origin, dest)) {
                            // 같은 곳(폰 안 / 같은 서버 안) 이동은 이름만 바꾸면 끝 — 빠름
                            progress.name = item.name
                            moveSame(dest, item.path, target)
                            progress.files += if (item.isDirectory) 0 else 1
                            progress.bytes += if (item.isDirectory) 0 else item.size
                        } else {
                            copyAny(c.origin, item, dest, target, progress)
                            if (c.move) deleteAny(c.origin, item)
                        }
                        ok++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += "${item.name}: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                _clip.value = null
                _state.value = State.Done(buildString {
                    append(if (c.move) "옮기기" else "복사")
                    append(" — ${ok}개 완료")
                    if (failed.isNotEmpty()) append("\n실패 ${failed.size}개:\n" + failed.joinToString("\n"))
                })
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State.Done("중지했어요 — ${ok}개는 끝났어요" + if (c.move) "\n(남은 파일은 원래 자리에 그대로 있어요)" else "")
            } finally {
                releaseLocks(locks)
            }
        }
    }

    private inner class Progress(val title: String, val fileCount: Int, val bytesTotal: Long) {
        var files = 0
            set(v) { field = v; publish() }
        var bytes = 0L
            set(v) { field = v; publish() }
        var name = ""
            set(v) { field = v; publish() }
        private var last = 0L
        fun publish(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - last < 150) return
            last = now
            _state.value = State.Working(title, files, fileCount, name, bytes, bytesTotal)
        }
    }

    /** 폴더 안 파일 수·전체 크기. */
    private suspend fun measure(origin: ThumbOrigin, path: String): Pair<Int, Long> {
        var n = 0
        var b = 0L
        for (e in list(origin, path, hidden = true)) {
            if (e.isDirectory) {
                val (cn, cb) = measure(origin, e.path); n += cn; b += cb
            } else { n++; b += e.size }
        }
        return n to b
    }

    /** 파일이나 폴더(안까지)를 복사. */
    private suspend fun copyAny(from: ThumbOrigin, item: FileEntry, to: ThumbOrigin, target: String, p: Progress) {
        coroutineContext.ensureActive()
        if (item.isDirectory) {
            makeDir(to, target)
            for (child in list(from, item.path, hidden = true)) {
                copyAny(from, child, to, joinPath(target, child.name), p)
            }
            return
        }
        p.name = item.name
        p.publish(force = true)
        val base = p.bytes
        try {
            copyFile(from, item, to, target) { n -> p.bytes = base + n }
        } catch (e: Exception) {
            withContext(NonCancellable) { runCatching { deleteFile(to, target) } } // 반쯤 만들어진 파일 정리
            throw e
        }
        p.bytes = base + item.size
        p.files += 1
    }

    private suspend fun copyFile(from: ThumbOrigin, item: FileEntry, to: ThumbOrigin, target: String, onBytes: (Long) -> Unit) {
        val src = from.server
        val dst = to.server
        val job = coroutineContext[Job]
        fun wrap(input: InputStream) = Counting(input, onBytes) { job?.isActive == false }
        when {
            src == null && dst == null -> withContext(Dispatchers.IO) {
                FileInputStream(item.path).use { input ->
                    FileOutputStream(target).use { out -> wrap(input).copyTo(out, 256 * 1024) }
                }
            }
            src == null && dst != null -> {
                val ok = FileInputStream(item.path).use { writeConn(dst).upload(target, wrap(it)) }
                if (!ok) throw UserFacingException("서버에 올리지 못했어요 (쓰기 권한 확인)")
            }
            src != null && dst == null -> readConn(src).download(item.path, 0) { input ->
                FileOutputStream(target).use { out -> wrap(input).copyTo(out, 256 * 1024) }
            }
            else -> {
                val ok = readConn(src!!).download(item.path, 0) { input ->
                    runBlocking { writeConn(dst!!).upload(target, wrap(input)) }
                }
                if (!ok) throw UserFacingException("서버에 올리지 못했어요 (쓰기 권한 확인)")
            }
        }
        if (job?.isActive == false) throw kotlinx.coroutines.CancellationException()
    }

    private suspend fun deleteAny(origin: ThumbOrigin, item: FileEntry) {
        if (item.isDirectory) {
            for (child in list(origin, item.path, hidden = true)) deleteAny(origin, child)
            val s = origin.server
            if (s != null) readConn(s).removeDir(item.path) else withContext(Dispatchers.IO) { File(item.path).delete() }
        } else deleteFile(origin, item.path)
    }

    // ───────────────────────── 공통 파일 작업

    private fun sameOrigin(a: ThumbOrigin, b: ThumbOrigin) = a.tag == b.tag

    private suspend fun list(origin: ThumbOrigin, path: String, hidden: Boolean): List<FileEntry> {
        val s = origin.server
        if (s != null) return readConn(s).list(path, includeHidden = hidden)
        return withContext(Dispatchers.IO) {
            val files = File(path).listFiles() ?: throw UserFacingException("폴더를 열 수 없어요: $path")
            files.filter { hidden || !it.name.startsWith(".") }.map {
                FileEntry(it.name, it.absolutePath, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified())
            }
        }
    }

    private suspend fun makeDir(origin: ThumbOrigin, path: String) {
        val s = origin.server
        if (s != null) {
            if (!readConn(s).makeDir(path)) throw UserFacingException("서버에 폴더를 만들지 못했어요 (쓰기 권한 확인)")
        } else withContext(Dispatchers.IO) {
            val f = File(path)
            if (!f.mkdirs() && !f.isDirectory) throw UserFacingException("폴더를 만들지 못했어요 (저장공간 권한 확인)")
        }
    }

    private suspend fun moveSame(origin: ThumbOrigin, from: String, to: String) {
        val s = origin.server
        if (s != null) {
            readConn(s).rename(from, to)
        } else withContext(Dispatchers.IO) {
            if (!File(from).renameTo(File(to))) throw UserFacingException("옮기지 못했어요 (저장공간 권한 확인)")
        }
    }

    private suspend fun deleteFile(origin: ThumbOrigin, path: String) {
        val s = origin.server
        if (s != null) readConn(s).delete(path) else withContext(Dispatchers.IO) { File(path).delete() }
    }

    /** 읽기용·쓰기용 연결을 따로 둔다 (받으면서 동시에 올려야 해서). */
    private fun readConn(s: FtpServer) = readConns.getOrPut(s) { FtpBrowser(s) }
    private fun writeConn(s: FtpServer) = writeConns.getOrPut(s) { FtpBrowser(s) }

    /** 옮긴 양을 세는 스트림 (중지하면 읽기를 끊음). */
    private class Counting(
        input: InputStream,
        private val onBytes: (Long) -> Unit,
        private val cancelled: () -> Boolean,
    ) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (cancelled()) return -1
            val n = super.read(b, off, len)
            if (n > 0) { count += n; onBytes(count) }
            return n
        }
        override fun close() { /* 바깥 스트림은 부른 쪽이 닫음 */ }
    }

    // 화면이 꺼져도 끊기지 않게
    private fun acquireLocks(): Pair<PowerManager.WakeLock?, WifiManager.WifiLock?> {
        val wl = runCatching {
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ComicViewer:transfer")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }.getOrNull()
        val wf = runCatching {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            context.getSystemService(WifiManager::class.java)
                ?.createWifiLock(mode, "ComicViewer:transfer")
                ?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        return wl to wf
    }

    private fun releaseLocks(l: Pair<PowerManager.WakeLock?, WifiManager.WifiLock?>) {
        runCatching { l.first?.takeIf { it.isHeld }?.release() }
        runCatching { l.second?.takeIf { it.isHeld }?.release() }
    }

    companion object {
        fun cleanName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().trimEnd('.')

        fun uniqueName(name: String, taken: Set<String>): String {
            if (name !in taken) return name
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var i = 2
            while ("$base ($i)$ext" in taken) i++
            return "$base ($i)$ext"
        }

        @Volatile private var instance: FileTransfer? = null
        fun get(context: Context): FileTransfer =
            instance ?: synchronized(this) { instance ?: FileTransfer(context.applicationContext).also { instance = it } }
    }
}
