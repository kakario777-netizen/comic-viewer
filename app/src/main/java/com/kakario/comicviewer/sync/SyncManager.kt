package com.kakario.comicviewer.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.kakario.comicviewer.data.FtpServer
import com.kakario.comicviewer.data.ProgressStore
import com.kakario.comicviewer.data.ServerStore
import com.kakario.comicviewer.ftp.FtpBrowser
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.joinPath
import com.kakario.comicviewer.model.parentPath
import com.kakario.comicviewer.thumb.ThumbOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * FTP 서버 동기화 (이북리더 앱과 같은 규칙).
 *  - 책 폴더마다 숨김 폴더 .cvsync/
 *  - 표지: .cvsync/<책파일이름>.<크기>.jpg (긴 변 360px, JPEG 85)
 *  - 읽기 기록: .cvsync/progress.json — 책별로 time이 더 최근인 쪽을 남김
 *  - 내 폰 파일은 대상 아님
 */
class SyncManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progress = ProgressStore.get(context)
    private val conns = ConcurrentHashMap<FtpServer, FtpBrowser>()
    private val folderLocks = ConcurrentHashMap<String, Mutex>()
    private val pendingSaves = ConcurrentHashMap<String, Job>()
    private val dir = File(context.filesDir, "sync").apply { mkdirs() }
    private val device: String = (Build.MODEL ?: "android").ifBlank { "android" }

    private fun conn(s: FtpServer) = conns.getOrPut(s) { FtpBrowser(s) }
    private fun lockFor(s: FtpServer, folder: String) = folderLocks.getOrPut("${s.id}|$folder") { Mutex() }

    // ───────────────────────── 표지

    private data class CoverIndex(val names: Set<String>, val at: Long)
    private val coverIndex = ConcurrentHashMap<String, CoverIndex>()

    /** 서버 .cvsync 안 표지 목록 (폴더마다 한 번 읽고 잠깐 기억). */
    private suspend fun coversIn(s: FtpServer, folder: String): Set<String> {
        val key = "${s.id}|$folder"
        coverIndex[key]?.takeIf { System.currentTimeMillis() - it.at < 120_000 }?.let { return it.names }
        val names = runCatching {
            conn(s).list(syncDir(folder), includeHidden = true).filter { !it.isDirectory }.map { it.name }.toSet()
        }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else emptySet() }
        coverIndex[key] = CoverIndex(names, System.currentTimeMillis())
        return names
    }

    /** 서버에 이 책 표지가 있으면 받아온다 (JPEG 바이트). */
    suspend fun fetchCover(s: FtpServer, entry: FileEntry): ByteArray? {
        val folder = parentPath(entry.path)
        val name = coverName(entry)
        if (name !in coversIn(s, folder)) return null
        return runCatching { conn(s).readSmall(joinPath(syncDir(folder), name)) }
            .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else null }
            ?.takeIf { it.isNotEmpty() }
    }

    /** 만든 표지를 바로 서버에 올린다 (뒤에서 조용히). */
    fun uploadCoverLater(s: FtpServer, entry: FileEntry, jpeg: ByteArray) {
        scope.launch { runCatching { uploadCover(s, entry, jpeg) } }
    }

    private suspend fun uploadCover(s: FtpServer, entry: FileEntry, jpeg: ByteArray): Boolean {
        val folder = parentPath(entry.path)
        val c = conn(s)
        c.makeDir(syncDir(folder))
        val ok = c.writeSmall(joinPath(syncDir(folder), coverName(entry)), jpeg)
        if (ok) coverIndex["${s.id}|$folder"]?.let {
            coverIndex["${s.id}|$folder"] = it.copy(names = it.names + coverName(entry))
        }
        return ok
    }

    // ───────────────────────── 읽기 기록

    /** 서버 progress.json 의 books (없으면 빈 것). 연결이 안 되면 예외. */
    private suspend fun readBooks(s: FtpServer, folder: String): JSONObject {
        val bytes = conn(s).readSmall(joinPath(syncDir(folder), PROGRESS)) ?: return JSONObject()
        return runCatching { JSONObject(String(bytes, Charsets.UTF_8)).optJSONObject("books") }.getOrNull()
            ?: JSONObject()
    }

    private suspend fun writeBooks(s: FtpServer, folder: String, books: JSONObject): Boolean {
        val c = conn(s)
        c.makeDir(syncDir(folder))
        val json = JSONObject().put("version", 1).put("books", books)
        return c.writeSmall(joinPath(syncDir(folder), PROGRESS), json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun localRecord(key: String, size: Long): JSONObject? {
        val p = progress.get(key)
        val time = progress.time(key)
        if (!p.started && time <= 0L) return null
        return JSONObject()
            .put("size", size)
            .put("pos", p.page)
            .put("total", p.total)
            .put("done", p.finished)
            .put("unit", "page")
            .put("time", time)
            .put("device", device)
    }

    /** 서버 기록이 더 최근이면 폰 기록을 바꾼다. 바꿨으면 true. */
    private fun applyRemote(key: String, size: Long, rec: JSONObject?): Boolean {
        if (rec == null || rec.optLong("size", -1) != size) return false
        if (rec.optString("unit", "page") != "page") return false
        val time = rec.optLong("time", 0)
        if (time <= progress.time(key)) return false
        val total = rec.optInt("total", 0)
        var pos = rec.optInt("pos", 0)
        if (rec.optBoolean("done", false) && total > 0) pos = total - 1
        progress.setRemote(key, pos.coerceIn(0, maxOf(0, total - 1)), total, time)
        return true
    }

    /**
     * 폴더 하나의 기록 맞추기: 서버를 받아 책별로 최근 것을 남기고,
     * 폰 쪽이 더 최근인 게 있으면 합쳐서 다시 올린다. 서버가 더 최근이면 폰 기록에 반영.
     * names: 이 폴더 안 책 이름 → 크기 (알면). null이면 폰 기록에 있는 것만.
     */
    private suspend fun syncFolder(s: FtpServer, origin: ThumbOrigin, folder: String, names: Map<String, Long>?): Boolean =
        lockFor(s, folder).withLock {
            val books = readBooks(s, folder)
            // 이 폴더에 대한 폰 기록들
            val local = HashMap<String, Pair<String, Long>>() // 책이름 → (key, size)
            progress.keysIn(origin.tag, folder).forEach { (path, size, key) ->
                local[path.substringAfterLast('/')] = key to size
            }
            names?.forEach { (n, size) ->
                if (n !in local) local[n] = progress.keyFor(origin, FileEntry(n, joinPath(folder, n), false, size, 0)) to size
            }
            var changedLocal = false
            var needUpload = false
            for ((name, ks) in local) {
                val (key, size) = ks
                val remote = books.optJSONObject(name)
                if (applyRemote(key, size, remote)) { changedLocal = true; continue }
                val mine = localRecord(key, size) ?: continue
                val remoteTime = if (remote != null && remote.optLong("size", -1) == size) remote.optLong("time", 0) else -1L
                if (remote == null || remote.optLong("size", -1) != size || mine.optLong("time") > remoteTime) {
                    // 크기가 다르면(다른 파일) 더 최근 쪽 기준 — 폰 쪽이 기록이 있으면 덮음
                    if (remote != null && remote.optLong("size", -1) != size && remote.optLong("time", 0) > mine.optLong("time")) continue
                    books.put(name, mine)
                    needUpload = true
                }
            }
            if (changedLocal) progress.bump()
            if (needUpload) writeBooks(s, folder, books) else true
        }

    /** 폴더 목록을 열 때: 서버 기록 반영 + 밀린 폰 기록 올리기. */
    fun pullFolder(origin: ThumbOrigin, folder: String, entries: List<FileEntry>) {
        val s = origin.server ?: return
        scope.launch {
            val ok = runCatching {
                syncFolder(s, origin, folder, entries.filter { it.isComicArchive }.associate { it.name to it.size })
            }.getOrDefault(false)
            if (ok) removePending(s, folder)
            retryPending(s)
        }
    }

    /** 책을 열기 직전: 서버 기록이 더 최근이면 그 위치로. (짧게 기다리고, 안 되면 폰 기록) */
    suspend fun pullBook(origin: ThumbOrigin, entry: FileEntry) {
        val s = origin.server ?: return
        val folder = parentPath(entry.path)
        runCatching {
            val books = lockFor(s, folder).withLock { readBooks(s, folder) }
            applyRemote(progress.keyFor(origin, entry), entry.size, books.optJSONObject(entry.name))
        }
    }

    /** 페이지를 넘길 때: 3초 뒤 저장 (연달아 넘기면 마지막 것만). */
    fun onPage(origin: ThumbOrigin, entry: FileEntry) {
        val s = origin.server ?: return
        val key = progress.keyFor(origin, entry)
        pendingSaves.remove(key)?.cancel()
        pendingSaves[key] = scope.launch {
            delay(3000)
            pendingSaves.remove(key)
            save(s, origin, entry)
        }
    }

    /** 책을 닫을 때 / 진행률 초기화했을 때: 바로 저장. */
    fun flush(origin: ThumbOrigin, entry: FileEntry) {
        val s = origin.server ?: return
        pendingSaves.remove(progress.keyFor(origin, entry))?.cancel()
        scope.launch { save(s, origin, entry) }
    }

    private suspend fun save(s: FtpServer, origin: ThumbOrigin, entry: FileEntry) {
        val folder = parentPath(entry.path)
        val ok = runCatching { syncFolder(s, origin, folder, mapOf(entry.name to entry.size)) }.getOrDefault(false)
        if (!ok) addPending(s, folder)
    }

    // 연결이 안 돼서 못 올린 폴더들 — 다음에 연결되면 다시
    private val pendingFile = File(dir, "pending.txt")
    private val pendingLock = Any()

    private fun addPending(s: FtpServer, folder: String) = synchronized(pendingLock) {
        val line = "${s.id}\t$folder"
        val cur = readPending()
        if (line !in cur) runCatching { pendingFile.appendText(line + "\n") }
    }

    private fun removePending(s: FtpServer, folder: String) = synchronized(pendingLock) {
        val line = "${s.id}\t$folder"
        val cur = readPending()
        if (line in cur) runCatching { pendingFile.writeText((cur - line).joinToString("") { it + "\n" }) }
    }

    private fun readPending(): List<String> =
        runCatching { pendingFile.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } }.getOrNull().orEmpty()

    private suspend fun retryPending(s: FtpServer) {
        val mine = synchronized(pendingLock) { readPending() }.filter { it.substringBefore('\t') == s.id.toString() }
        for (line in mine) {
            val folder = line.substringAfter('\t')
            val ok = runCatching { syncFolder(s, ThumbOrigin(s), folder, null) }.getOrDefault(false)
            if (ok) removePending(s, folder) else return
        }
    }

    // ───────────────────────── 처음 한 번 올리기 (업데이트 후)

    data class MigrateStatus(val done: Int, val total: Int?, val folders: Int)

    private val _migrate = MutableStateFlow<MigrateStatus?>(null)
    /** null이면 숨김. 화면 위 "썸네일·읽기 정보 서버 동기화 중 (123/850)" */
    val migrate: StateFlow<MigrateStatus?> = _migrate

    private val completeFile = File(dir, "migrate_complete")
    private val tasksFile = File(dir, "migrate_tasks.tsv")
    private val nextFile = File(dir, "migrate_next.txt")
    private val crawledFile = File(dir, "migrate_crawled.txt")
    private var migrateJob: Job? = null

    /**
     * 앱을 켤 때 부른다. 끝났으면 아무것도 안 함.
     * thumbFile: 표지 캐시 파일 찾기 (ThumbnailLoader).
     */
    fun startMigration(thumbFile: (ThumbOrigin, FileEntry) -> File?) {
        if (completeFile.exists() || migrateJob?.isActive == true) {
            if (completeFile.exists()) scope.launch { ServerStore(context).load().forEach { runCatching { retryPending(it) } } }
            return
        }
        migrateJob = scope.launch {
            try {
                runMigration(thumbFile)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                _migrate.value = null
            }
        }
    }

    private suspend fun runMigration(thumbFile: (ThumbOrigin, FileEntry) -> File?) {
        val servers = ServerStore(context).load()
        val byId = servers.associateBy { it.id.toString() }
        val crawled = runCatching { crawledFile.readLines().filter { it.isNotBlank() }.toMutableSet() }
            .getOrDefault(mutableSetOf())

        // 1) 할 일 목록 만들기 (서버마다 한 번, 끝난 서버는 다시 안 함)
        val firstTime = !tasksFile.exists()
        if (firstTime) {
            // 읽기 기록: 폴더 단위
            val folders = progress.allFtpKeys().map { it.tag to parentPath(it.path) }.toSet()
            tasksFile.writeText(folders.joinToString("") { (tag, folder) -> "P\t${tag.removePrefix("ftp")}\t$folder\n" })
            nextFile.writeText("0")
        }
        for (s in servers) {
            if (s.id.toString() in crawled) continue
            val found = mutableListOf<String>()
            var folders = 0
            _migrate.value = MigrateStatus(0, null, 0)
            val ok = runCatching {
                val c = conn(s)
                val root = c.connect()
                val queue = ArrayDeque<Pair<String, Int>>().apply { add(root to 0) }
                while (queue.isNotEmpty() && folders < 30_000) {
                    val (path, depth) = queue.removeFirst()
                    val list = runCatching { c.list(path) }.getOrNull() ?: continue
                    folders++
                    if (folders % 20 == 0) _migrate.value = MigrateStatus(0, null, folders)
                    for (e in list) {
                        if (e.isDirectory) {
                            if (depth < 15) queue.add(e.path to depth + 1)
                        } else if (e.isComicArchive) {
                            val f = thumbFile(ThumbOrigin(s), e)
                            if (f != null && f.exists()) found += "T\t${s.id}\t${e.path}\t${e.size}\t${e.modified}"
                        }
                    }
                }
            }.isSuccess
            if (!ok) continue // 서버가 꺼져 있음 → 다음에 켤 때
            tasksFile.appendText(found.joinToString("") { it + "\n" })
            crawledFile.appendText("${s.id}\n")
            crawled += s.id.toString()
        }

        // 2) 차례로 올리기 (중간에 꺼져도 다음 번호부터)
        val tasks = tasksFile.readLines().filter { it.isNotBlank() }
        var next = runCatching { nextFile.readText().trim().toInt() }.getOrDefault(0)
        while (next < tasks.size) {
            _migrate.value = MigrateStatus(next, tasks.size, 0)
            val t = tasks[next].split('\t')
            val s = byId[t.getOrNull(1)]
            if (s != null) {
                var tries = 0
                while (true) {
                    val ok = runCatching { runTask(s, t, thumbFile) }.getOrDefault(false)
                    if (ok) break
                    tries++
                    if (tries >= 2) {
                        // 서버 자체가 안 되면 멈추고 다음에 이어서, 서버는 되는데 이 항목만 안 되면 건너뜀
                        val alive = runCatching { conn(s).connect() }.isSuccess
                        if (!alive) return
                        break
                    }
                    delay(1500)
                }
            }
            next++
            nextFile.writeText(next.toString())
        }
        if (servers.all { it.id.toString() in crawled }) {
            completeFile.writeText("1")
            tasksFile.delete(); nextFile.delete(); crawledFile.delete()
        }
    }

    private suspend fun runTask(s: FtpServer, t: List<String>, thumbFile: (ThumbOrigin, FileEntry) -> File?): Boolean =
        when (t[0]) {
            "P" -> syncFolder(s, ThumbOrigin(s), t[2], null)
            "T" -> {
                val path = t[2]
                val entry = FileEntry(path.substringAfterLast('/'), path, false, t[3].toLong(), t[4].toLong())
                if (coverName(entry) in coversIn(s, parentPath(path))) true
                else {
                    val f = thumbFile(ThumbOrigin(s), entry)
                    val jpeg = f?.takeIf { it.exists() }?.let { coverJpegFromFile(it) }
                    if (jpeg == null) true else uploadCover(s, entry, jpeg)
                }
            }
            else -> true
        }

    companion object {
        const val SYNC_DIR = ".cvsync"
        const val PROGRESS = "progress.json"
        const val COVER_MAX = 360

        fun syncDir(folder: String) = joinPath(folder, SYNC_DIR)
        fun coverName(entry: FileEntry) = "${entry.name}.${entry.size}.jpg"

        /** 긴 변 최대 360px, 컬러 그대로, JPEG 85. */
        fun coverJpeg(bmp: Bitmap): ByteArray {
            val longest = maxOf(bmp.width, bmp.height)
            val scaled = if (longest > COVER_MAX) {
                val k = COVER_MAX.toFloat() / longest
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * k).toInt().coerceAtLeast(1),
                    (bmp.height * k).toInt().coerceAtLeast(1),
                    true,
                )
            } else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (scaled !== bmp) scaled.recycle()
            return out.toByteArray()
        }

        private fun coverJpegFromFile(f: File): ByteArray? {
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
            return try { coverJpeg(bmp) } finally { bmp.recycle() }
        }

        @Volatile private var instance: SyncManager? = null
        fun get(context: Context): SyncManager =
            instance ?: synchronized(this) { instance ?: SyncManager(context.applicationContext).also { instance = it } }
    }
}
