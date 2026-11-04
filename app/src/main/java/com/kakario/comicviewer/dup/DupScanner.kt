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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        /** 검사 끝 — 결과는 pending()으로 다시 읽는다. */
        data class Done(
            val key: String,
            val label: String,
            val checked: Int,
            val savedInFolder: Boolean,
        ) : State
        data class Failed(val key: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    /** 기록 파일을 고칠 때 한 번에 하나씩 (검사·무시·삭제가 겹쳐도 내용이 안 꼬이게). */
    private val indexLock = Mutex()

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
        val index = readIndex(origin, path).first?.prints ?: emptyMap()
        return DupLogic.diff(index, zips.map { Triple(it.name, it.size, it.modified) })
    }

    /** '보지 않음'을 누른 상태인지 — 폴더 목록이 다시 바뀌면(다음 갱신) 풀린다. */
    fun isMuted(origin: ThumbOrigin, path: String, entries: List<FileEntry>): Boolean =
        prefs.getInt("mute|" + keyOf(origin, path), 0) == listingHash(entries)

    fun mute(origin: ThumbOrigin, path: String, entries: List<FileEntry>) {
        prefs.edit().putInt("mute|" + keyOf(origin, path), listingHash(entries)).apply()
    }

    private fun listingHash(entries: List<FileEntry>): Int =
        entries.filter { it.isComicArchive }.map { "${it.name}|${it.size}|${it.modified}" }.sorted().hashCode()

    // ───── 검사

    /** 검사를 시작한다 (이미 다른 검사가 돌고 있으면 그건 멈추고 이걸로). */
    fun scan(origin: ThumbOrigin, path: String, label: String, entries: List<FileEntry>) {
        job?.cancel()
        val key = keyOf(origin, path)
        job = scope.launch {
            try {
                val zips = entries.filter { it.isComicArchive }
                val oldIndex = readIndex(origin, path).first
                val old = oldIndex?.prints ?: emptyMap()
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
                val inFolder = indexLock.withLock {
                    // 검사하는 사이 '무시'한 것도 잃지 않도록 최신 무시 목록을 다시 읽어 합친다
                    val ignored = (readIndex(origin, path).first?.ignored ?: emptySet()) + (oldIndex?.ignored ?: emptySet())
                    writeIndex(origin, path, prints, ignored)
                }
                _state.value = State.Done(key, label, prints.size, inFolder)
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

    /**
     * 아직 처리하지 않은 중복 묶음 (삭제·무시 안 한 것).
     * 기록이 아예 없으면(한 번도 검사 안 함) null.
     * presentNames를 주면 지금 폴더에 실제로 있는 파일만 따진다.
     */
    suspend fun pending(origin: ThumbOrigin, path: String, presentNames: Set<String>?): List<DupGroup>? {
        val index = readIndex(origin, path).first ?: return null
        val prints = index.prints.values.filter { presentNames == null || it.name in presentNames }
        return DupLogic.group(prints).filter { it.signature !in index.ignored }
    }

    /** 묶음 하나를 '무시' — 같은 파일 묶음이면 다시 안 보인다 (새 파일이 끼면 다시 보임). */
    suspend fun ignore(origin: ThumbOrigin, path: String, group: DupGroup) {
        indexLock.withLock {
            val (index, _) = readIndex(origin, path)
            val prints = index?.prints?.values ?: return
            writeIndex(origin, path, prints, index.ignored + group.signature)
        }
    }

    /** 파일을 지웠을 때 — 기록에서도 뺀다. */
    suspend fun forget(origin: ThumbOrigin, path: String, name: String) {
        indexLock.withLock {
            val (index, _) = readIndex(origin, path)
            if (index != null && name in index.prints) {
                writeIndex(origin, path, index.prints.values.filter { it.name != name }, index.ignored)
            }
        }
    }

    // ───── 기록 파일 (폴더 안 숨김 파일 → 안 되면 폰 안)

    /** (기록, 폴더 안에서 읽었는지) */
    private suspend fun readIndex(origin: ThumbOrigin, path: String): Pair<DupIndex?, Boolean> {
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
    private suspend fun writeIndex(
        origin: ThumbOrigin,
        path: String,
        prints: Collection<BookPrint>,
        ignored: Set<String>,
    ): Boolean {
        val data = DupLogic.encode(prints, ignored)
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
