package com.kakario.comicviewer.dup

import android.content.Context
import com.kakario.comicviewer.data.FtpServer
import com.kakario.comicviewer.ftp.FtpBrowser
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.RangeSource
import com.kakario.comicviewer.model.joinPath
import com.kakario.comicviewer.thumb.ThumbOrigin
import com.kakario.comicviewer.thumb.ZipCover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 중복 검사.
 *  - '중복 검사 폴더'로 지정한 폴더를 기억하고,
 *  - 폴더 안에 숨김 기록 파일(.comicviewer_dup.json)로 파일별 지문을 남겨서
 *  - 다음에 들어왔을 때 새로 들어오거나 바뀐 파일만 다시 검사한다.
 * 폴더에 쓸 권한이 없으면 기록은 폰 안에 대신 저장한다.
 */
class DupScanner private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("dup_watch", Context.MODE_PRIVATE)
    private val fallbackDir = File(app.filesDir, "dup").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ftpConnections = ConcurrentHashMap<FtpServer, FtpBrowser>()

    sealed interface State {
        data object Idle : State
        data class Running(val key: String, val label: String, val done: Int, val total: Int) : State
        data class Done(
            val key: String,
            val label: String,
            val groups: List<DupGroup>,
            val checked: Int,
            val savedInFolder: Boolean,
        ) : State
        data class Failed(val key: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    /** '나중에'를 누른 폴더 — 같은 목록이면 앱을 켜 있는 동안 다시 묻지 않음. */
    private val snoozed = ConcurrentHashMap<String, Int>()

    fun keyOf(origin: ThumbOrigin, path: String) = "${origin.tag}|${path.trimEnd('/')}"

    // ───── 중복 검사 폴더 지정

    fun isWatched(origin: ThumbOrigin, path: String): Boolean =
        prefs.getStringSet("folders", emptySet())!!.contains(keyOf(origin, path))

    fun setWatched(origin: ThumbOrigin, path: String, on: Boolean) {
        val set = prefs.getStringSet("folders", emptySet())!!.toMutableSet()
        if (on) set += keyOf(origin, path) else set -= keyOf(origin, path)
        prefs.edit().putStringSet("folders", set).apply()
    }

    // ───── 변경 감지

    /** 폴더 목록을 기록과 비교한다. 기록이 아예 없으면 전부 새 파일로 본다. */
    suspend fun checkChanges(origin: ThumbOrigin, path: String, entries: List<FileEntry>): FolderDiff {
        val zips = entries.filter { it.isComicArchive }
        val index = readIndex(origin, path).first ?: emptyMap()
        return DupLogic.diff(index, zips.map { Triple(it.name, it.size, it.modified) })
    }

    fun isSnoozed(origin: ThumbOrigin, path: String, entries: List<FileEntry>): Boolean =
        snoozed[keyOf(origin, path)] == listingHash(entries)

    fun snooze(origin: ThumbOrigin, path: String, entries: List<FileEntry>) {
        snoozed[keyOf(origin, path)] = listingHash(entries)
    }

    private fun listingHash(entries: List<FileEntry>): Int =
        entries.filter { it.isComicArchive }.map { "${it.name}|${it.size}|${it.modified}" }.sorted().hashCode()

    // ───── 검사

    /** 검사를 시작한다 (이미 다른 검사가 돌고 있으면 그건 멈추고 이걸로). */
    fun scan(origin: ThumbOrigin, path: String, label: String, entries: List<FileEntry>) {
        job?.cancel()
        val key = keyOf(origin, path)
        snoozed.remove(key)
        job = scope.launch {
            try {
                val zips = entries.filter { it.isComicArchive }
                val old = readIndex(origin, path).first ?: emptyMap()
                val prints = ArrayList<BookPrint>(zips.size)
                _state.value = State.Running(key, label, 0, zips.size)
                zips.forEachIndexed { i, e ->
                    val prev = old[e.name]
                    prints += if (DupLogic.upToDate(prev, e.size, e.modified)) {
                        prev!!.copy(modified = e.modified)
                    } else {
                        val fp = try {
                            withSource(origin, e) { src ->
                                ZipCover.listEntries(src)?.let { DupLogic.fingerprint(it) }
                            }
                        } catch (ex: kotlinx.coroutines.CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            null
                        }
                        BookPrint(e.name, e.size, e.modified, fp?.first, fp?.second ?: 0)
                    }
                    _state.value = State.Running(key, label, i + 1, zips.size)
                }
                val inFolder = writeIndex(origin, path, prints)
                _state.value = State.Done(key, label, DupLogic.group(prints), prints.size, inFolder)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(key, "중복 검사 중 문제가 생겼어요: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Idle
    }

    fun dismissResult() {
        if (_state.value !is State.Running) _state.value = State.Idle
    }

    /** 결과 창에서 파일을 지웠을 때 — 결과와 기록에서도 뺀다. */
    fun forget(origin: ThumbOrigin, path: String, name: String) {
        val st = _state.value
        if (st is State.Done && st.key == keyOf(origin, path)) {
            val groups = st.groups.map { g -> g.copy(items = g.items.filter { it.name != name }) }
                .filter { it.items.size >= 2 }
            _state.value = st.copy(groups = groups)
        }
        scope.launch {
            val (index, _) = readIndex(origin, path)
            if (index != null && name in index) writeIndex(origin, path, index.values.filter { it.name != name })
        }
    }

    // ───── 기록 파일 (폴더 안 숨김 파일 → 안 되면 폰 안)

    /** (기록, 폴더 안에서 읽었는지) */
    private suspend fun readIndex(origin: ThumbOrigin, path: String): Pair<Map<String, BookPrint>?, Boolean> {
        val target = joinPath(path, DupLogic.INDEX_FILE)
        val server = origin.server
        val inFolder = try {
            if (server != null) ftp(server).readSmall(target)
            else withContext(Dispatchers.IO) { File(target).takeIf { it.exists() }?.readBytes() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        DupLogic.decode(inFolder)?.let { return it to true }
        val local = withContext(Dispatchers.IO) { fallbackFile(origin, path).takeIf { it.exists() }?.readBytes() }
        return DupLogic.decode(local) to false
    }

    /** 폴더에 숨김 파일로 쓴다. 안 되면 폰 안에 저장하고 false. */
    private suspend fun writeIndex(origin: ThumbOrigin, path: String, prints: Collection<BookPrint>): Boolean {
        val data = DupLogic.encode(prints)
        val target = joinPath(path, DupLogic.INDEX_FILE)
        val server = origin.server
        val ok = try {
            if (server != null) {
                ftp(server).writeSmall(target, data)
            } else {
                withContext(Dispatchers.IO) { File(target).writeBytes(data); true }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        withContext(Dispatchers.IO) {
            val f = fallbackFile(origin, path)
            if (ok) f.delete() else f.writeBytes(data)
        }
        return ok
    }

    private fun fallbackFile(origin: ThumbOrigin, path: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(keyOf(origin, path).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(fallbackDir, "$hash.json")
    }

    private suspend fun <T> withSource(origin: ThumbOrigin, entry: FileEntry, block: (RangeSource) -> T): T {
        val server = origin.server
        if (server != null) return ftp(server).useFile(entry.path, entry.size, block)
        return withContext(Dispatchers.IO) {
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

    /** 중복 검사 전용 FTP 연결 (책장·표지·뷰어와 부딪히지 않게). */
    private fun ftp(server: FtpServer): FtpBrowser =
        ftpConnections.getOrPut(server) { FtpBrowser(server) }

    companion object {
        @Volatile private var instance: DupScanner? = null

        fun get(context: Context): DupScanner =
            instance ?: synchronized(this) {
                instance ?: DupScanner(context).also { instance = it }
            }
    }
}
