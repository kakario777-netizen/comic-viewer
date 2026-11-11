package com.kakario.comicviewer.fix

import android.content.Context
import android.graphics.BitmapFactory
import com.kakario.comicviewer.data.FtpServer
import com.kakario.comicviewer.ftp.FtpBrowser
import com.kakario.comicviewer.local.LocalStorage
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.RangeSource
import com.kakario.comicviewer.model.UserFacingException
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * 압축 오류 검사.
 *  - 표지가 안 나오는 ZIP을 살펴서 2중 압축 / 손상 / 암호 / 지원 안 하는 압축을 가려낸다.
 *  - 2중 압축은 (물어본 뒤) 안쪽 ZIP을 꺼내 일반 만화 파일로 바꾸고, 원본은 숨김 폴더(.comicviewer_original)로 옮긴다.
 *  - 오류 파일은 (물어본 뒤) 같은 폴더의 '오류난 파일' 폴더로 옮긴다.
 */
class ArchiveDoctor private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class Kind { OK, NESTED, BROKEN, UNKNOWN }

    data class Diagnosis(
        val entry: FileEntry,
        val kind: Kind,
        val reason: String,
        val inner: List<ZipCover.Entry> = emptyList(),
    )

    data class Report(
        val key: String,
        val origin: ThumbOrigin,
        val path: String,
        val label: String,
        val items: List<Diagnosis>,
        /** 길게 눌러 직접 검사한 경우 — 이상이 없어도 결과를 보여줌 */
        val manual: Boolean,
    ) {
        val nested get() = items.filter { it.kind == Kind.NESTED }
        val broken get() = items.filter { it.kind == Kind.BROKEN }
        val unknown get() = items.filter { it.kind == Kind.UNKNOWN }
        val okCount get() = items.count { it.kind == Kind.OK }
    }

    sealed interface State {
        data object Idle : State
        data class Working(val key: String, val title: String, val done: Int, val total: Int, val detail: String) : State
        data class Done(val key: String, val message: String) : State
    }

    private val _reports = MutableStateFlow<Map<String, Report>>(emptyMap())
    /** 폴더별로 아직 확인 안 한 검사 결과 */
    val reports: StateFlow<Map<String, Report>> = _reports

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    private val readConns = ConcurrentHashMap<FtpServer, FtpBrowser>()
    private val writeConns = ConcurrentHashMap<FtpServer, FtpBrowser>()

    fun keyOf(origin: ThumbOrigin, path: String) = "${origin.tag}|${path.trimEnd('/')}"

    // ───────────────────────── 검사

    /** 길게 누르기 '압축 오류 검사하기' — 진행 창을 띄우고 결과를 보여준다. */
    fun checkNow(origin: ThumbOrigin, path: String, label: String, targets: List<FileEntry>) {
        job?.cancel()
        val key = keyOf(origin, path)
        job = scope.launch {
            try {
                check(origin, path, label, targets, manual = true)
            } finally {
                if (_state.value is State.Working) _state.value = State.Idle
            }
        }
    }

    /** 검사 본체. manual = false면 이상 있는 파일이 있을 때만 결과를 남긴다. */
    suspend fun check(origin: ThumbOrigin, path: String, label: String, targets: List<FileEntry>, manual: Boolean) {
        val key = keyOf(origin, path)
        val zips = targets.filter { it.isComicArchive }
        val out = ArrayList<Diagnosis>(zips.size)
        zips.forEachIndexed { i, e ->
            if (manual) _state.value = State.Working(key, "압축 오류 검사 중", i, zips.size, e.name)
            out += diagnose(origin, e)
        }
        if (manual) _state.value = State.Idle
        val report = Report(key, origin, path, label, out, manual)
        if (manual || report.nested.isNotEmpty() || report.broken.isNotEmpty()) {
            _reports.value = _reports.value + (key to report)
        }
    }

    fun dismiss(key: String) {
        _reports.value = _reports.value - key
    }

    fun clearDone() {
        if (_state.value is State.Done) _state.value = State.Idle
    }

    private suspend fun diagnose(origin: ThumbOrigin, e: FileEntry): Diagnosis = try {
        withSource(origin, e.path, e.size) { src ->
            val entries = ZipCover.listEntries(src)
                ?: return@withSource Diagnosis(e, Kind.BROKEN, "ZIP 목차를 읽을 수 없어요 (손상됐거나 ZIP이 아님)")
            val pages = ZipCover.imagePages(entries)
            if (pages.isNotEmpty()) {
                val bytes = ZipCover.extract(src, pages.first())
                if (bytes == null) {
                    Diagnosis(e, Kind.BROKEN, "이미지를 풀 수 없어요 (지원 안 하는 압축 방식)")
                } else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    if (o.outWidth <= 0) Diagnosis(e, Kind.BROKEN, "첫 이미지가 깨져 있어요")
                    else Diagnosis(e, Kind.OK, "정상")
                }
            } else {
                val files = entries.filter { !it.name.endsWith("/") && !it.name.contains("__MACOSX") }
                val ext = { n: String -> n.substringAfterLast('.', "").lowercase() }
                val inner = files.filter { ext(it.name) in INNER_ZIP }
                val otherArchive = files.firstOrNull { ext(it.name) in OTHER_ARCHIVE }
                when {
                    inner.isNotEmpty() -> Diagnosis(e, Kind.NESTED, "2중 압축 (안에 ZIP ${inner.size}개)", inner)
                    files.any { (it.flags and 1) != 0 } -> Diagnosis(e, Kind.BROKEN, "암호가 걸린 압축파일이에요")
                    otherArchive != null -> Diagnosis(e, Kind.BROKEN, "안에 ${ext(otherArchive.name).uppercase()} 압축이 있어요 (지원 안 함)")
                    else -> Diagnosis(e, Kind.BROKEN, "안에 만화 이미지가 없어요")
                }
            }
        }
    } catch (ex: kotlinx.coroutines.CancellationException) {
        throw ex
    } catch (ex: Exception) {
        // 연결 문제 등으로 확인 못 함 — 오류 파일로 단정하지 않는다
        Diagnosis(e, Kind.UNKNOWN, "확인 못 함 (연결 문제)")
    }

    // ───────────────────────── 고치기

    /**
     * 2중 압축 파일들을 풀어서 일반 만화 파일로 바꾼다.
     * names: 바깥 파일 경로 → 꺼낼 파일 이름들(확장자 빼고, 안쪽 ZIP 순서대로). 없으면 기본 이름.
     * only: 지정하면 그 파일들만 푼다.
     */
    fun fixNested(report: Report, names: Map<String, List<String>> = emptyMap(), only: Set<String>? = null) =
        runFix(report, "2중 압축 푸는 중", report.nested.filter { only == null || it.entry.path in only }) { d, i, n ->
        unnest(report.origin, report.path, d, names[d.entry.path]) { detail ->
            _state.value = State.Working(report.key, "2중 압축 푸는 중", i, n, "${d.entry.name}\n$detail")
        }
    }

    /** 오류 파일들을 '오류난 파일' 폴더로 옮긴다. */
    fun moveBroken(report: Report) = runFix(report, "오류 파일 옮기는 중", report.broken) { d, _, _ ->
        val errDir = joinPath(report.path, ERROR_DIR)
        makeDir(report.origin, errDir)
        val taken = runCatching { list(report.origin, errDir).map { it.name }.toSet() }.getOrDefault(emptySet())
        rename(report.origin, d.entry.path, joinPath(errDir, uniqueName(d.entry.name, taken)))
    }

    private fun runFix(
        report: Report,
        title: String,
        items: List<Diagnosis>,
        action: suspend (Diagnosis, Int, Int) -> Unit,
    ) {
        if (items.isEmpty()) return
        job?.cancel()
        job = scope.launch {
            var ok = 0
            val failed = mutableListOf<String>()
            val handled = mutableSetOf<String>()
            items.forEachIndexed { i, d ->
                _state.value = State.Working(report.key, title, i, items.size, d.entry.name)
                try {
                    action(d, i, items.size)
                    ok++
                    handled += d.entry.path
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += "${d.entry.name}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            // 처리한 파일은 결과에서 빼기
            val cur = _reports.value[report.key]
            if (cur != null) {
                val left = cur.copy(items = cur.items.filter { it.entry.path !in handled })
                _reports.value = if (left.nested.isEmpty() && left.broken.isEmpty()) _reports.value - report.key
                else _reports.value + (report.key to left)
            }
            val msg = buildString {
                append("$title — ${ok}개 완료")
                if (failed.isNotEmpty()) append("\n실패 ${failed.size}개:\n" + failed.joinToString("\n"))
            }
            _state.value = State.Done(report.key, msg.replace(" 중 —", " —"))
        }
    }

    /**
     * 2중 압축 하나 풀기:
     *  1) 안쪽 ZIP을 임시 숨김 이름(.cvtmp_…)으로 꺼내 올리고
     *  2) 정상 만화 파일인지 확인한 뒤
     *  3) 원본은 .comicviewer_original 숨김 폴더로 옮기고, 임시 파일을 제 이름으로 바꾼다.
     * 안쪽 ZIP이 1개면 원래 파일 이름 그대로, 여러 개면 각자 이름으로 꺼낸다.
     */
    private suspend fun unnest(
        origin: ThumbOrigin,
        folder: String,
        d: Diagnosis,
        names: List<String>?,
        progress: (String) -> Unit,
    ) {
        // 원본은 숨김 폴더로 옮겨지니 원본 이름은 다시 써도 됨
        val taken = list(origin, folder).map { it.name }.toMutableSet().apply { remove(d.entry.name) }
        val defaults = defaultNames(d)
        val targets = d.inner.mapIndexed { i, inner ->
            val base = cleanName(names?.getOrNull(i)).ifEmpty { defaults[i] }
            val ext = inner.name.substringAfterLast('.', "zip").lowercase().let { if (it in INNER_ZIP) it else "zip" }
            val n = uniqueName("$base.$ext", taken)
            taken += n
            inner to n
        }
        val made = mutableListOf<Pair<String, String>>() // 임시 경로 → 최종 이름
        try {
            for ((inner, dest) in targets) {
                if (inner.method != 0 && inner.method != 8) throw UserFacingException("지원 안 하는 압축 방식이에요")
                val tmp = joinPath(folder, ".cvtmp_$dest")
                // 안쪽 ZIP 데이터가 시작하는 위치 (로컬 헤더 뒤)
                val dataStart = withSource(origin, d.entry.path, d.entry.size) { src ->
                    val h = src.read(inner.localOffset, 30)
                    if (h.size < 30 || u32(h, 0) != 0x04034b50L) throw UserFacingException("압축파일 구조가 이상해요")
                    inner.localOffset + 30 + u16(h, 26) + u16(h, 28)
                }
                copyOut(origin, d.entry.path, dataStart, inner, tmp, progress)
                made += tmp to dest
                progress("확인하는 중")
                val good = withSource(origin, tmp, inner.size) { src ->
                    ZipCover.listEntries(src)?.let { ZipCover.imagePages(it).isNotEmpty() } ?: false
                }
                if (!good) throw UserFacingException("꺼낸 파일도 정상 만화가 아니에요")
            }
            // 원본은 숨김 폴더로
            val hidden = joinPath(folder, ORIGINAL_DIR)
            makeDir(origin, hidden)
            val hiddenTaken = runCatching { list(origin, hidden).map { it.name }.toSet() }.getOrDefault(emptySet())
            rename(origin, d.entry.path, joinPath(hidden, uniqueName(d.entry.name, hiddenTaken)))
            for ((tmp, dest) in made) rename(origin, tmp, joinPath(folder, dest))
        } catch (e: Exception) {
            // 실패하면 만든 임시 파일 정리 (원본은 그대로)
            for ((tmp, _) in made) runCatching { delete(origin, tmp) }
            throw e
        }
    }

    /** 바깥 ZIP 안의 항목 하나를 풀면서 그대로 새 파일로 쓴다 (전체를 폰에 받아두지 않음). */
    private suspend fun copyOut(
        origin: ThumbOrigin,
        outerPath: String,
        dataStart: Long,
        inner: ZipCover.Entry,
        dest: String,
        progress: (String) -> Unit,
    ) {
        fun wrap(raw: InputStream): InputStream {
            val limited = Limited(raw, inner.compressedSize, padOne = inner.method == 8)
            val data = if (inner.method == 8) InflaterInputStream(limited, Inflater(true), 64 * 1024) else limited
            return Counting(data, inner.size, progress)
        }
        val server = origin.server
        if (server != null) {
            val ok = readConn(server).download(outerPath, dataStart) { raw ->
                runBlocking { writeConn(server).upload(dest, wrap(raw)) }
            }
            if (!ok) throw UserFacingException("서버에 파일을 쓰지 못했어요 (쓰기 권한 확인)")
        } else {
            withContext(Dispatchers.IO) {
                RandomAccessFile(outerPath, "r").use { raf ->
                    raf.seek(dataStart)
                    val input = wrap(Channels.newInputStream(raf.channel))
                    FileOutputStream(dest).use { out -> input.copyTo(out, 64 * 1024) }
                }
            }
        }
    }

    // ───────────────────────── FTP / 내 폰 공통 파일 작업

    private suspend fun list(origin: ThumbOrigin, path: String): List<FileEntry> {
        val s = origin.server
        return if (s != null) readConn(s).list(path) else LocalStorage.list(path)
    }

    private suspend fun makeDir(origin: ThumbOrigin, path: String) {
        val s = origin.server
        if (s != null) readConn(s).makeDir(path) else withContext(Dispatchers.IO) { File(path).mkdirs() }
    }

    private suspend fun rename(origin: ThumbOrigin, from: String, to: String) {
        val s = origin.server
        if (s != null) {
            readConn(s).rename(from, to)
        } else withContext(Dispatchers.IO) {
            if (!File(from).renameTo(File(to))) throw UserFacingException("옮기지 못했어요 (저장공간 권한 확인)")
        }
    }

    private suspend fun delete(origin: ThumbOrigin, path: String) {
        val s = origin.server
        if (s != null) readConn(s).delete(path) else withContext(Dispatchers.IO) { File(path).delete() }
    }

    private suspend fun <T> withSource(origin: ThumbOrigin, path: String, size: Long, block: (RangeSource) -> T): T {
        val s = origin.server
        if (s != null) return readConn(s).useFile(path, size, block)
        return withContext(Dispatchers.IO) {
            RandomAccessFile(path, "r").use { raf ->
                block(object : RangeSource {
                    override val size: Long = raf.length()
                    override fun read(offset: Long, length: Int): ByteArray {
                        val n = minOf(length.toLong(), this.size - offset).coerceAtLeast(0).toInt()
                        val buf = ByteArray(n)
                        raf.seek(offset)
                        raf.readFully(buf)
                        return buf
                    }
                })
            }
        }
    }

    /** 읽기용·쓰기용 연결을 따로 둔다 (꺼내면서 동시에 올려야 해서). */
    private fun readConn(s: FtpServer) = readConns.getOrPut(s) { FtpBrowser(s) }
    private fun writeConn(s: FtpServer) = writeConns.getOrPut(s) { FtpBrowser(s) }

    /** 정해진 길이까지만 읽는 스트림. padOne이면 끝에 0 한 바이트를 더 줘서 압축 풀기가 끝을 확실히 알게 함. */
    private class Limited(input: InputStream, private var left: Long, private val padOne: Boolean) : FilterInputStream(input) {
        private var padded = false
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) {
                if (padOne && !padded) { padded = true; b[off] = 0; return 1 }
                return -1
            }
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun close() { /* 바깥 스트림은 부른 쪽이 닫음 */ }
    }

    /** 옮긴 양을 보여주는 스트림. */
    private class Counting(input: InputStream, private val total: Long, private val report: (String) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var lastMb = -1L
        override fun read(): Int = super.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bump(it) }
        private fun bump(n: Int) {
            count += n
            val mb = count / (1024 * 1024)
            if (mb != lastMb) {
                lastMb = mb
                report(if (total > 0) "$mb / ${total / (1024 * 1024)} MB 옮기는 중" else "$mb MB 옮기는 중")
            }
        }
    }

    companion object {
        const val ORIGINAL_DIR = ".comicviewer_original"
        const val ERROR_DIR = "오류난 파일"
        private val INNER_ZIP = setOf("zip", "cbz")
        private val OTHER_ARCHIVE = setOf("rar", "cbr", "7z", "cb7", "alz", "egg", "tar", "gz")

        /** 꺼낼 파일 기본 이름(확장자 빼고): 안쪽이 1개면 바깥 파일 이름, 여러 개면 안쪽 파일 이름. */
        fun defaultNames(d: Diagnosis): List<String> =
            if (d.inner.size == 1) listOf(baseName(d.entry.name))
            else d.inner.map { baseName(it.name.substringAfterLast('/')) }

        fun baseName(name: String) = name.substringBeforeLast('.', name)

        /** 파일 이름에 못 쓰는 글자 정리. */
        fun cleanName(name: String?): String =
            (name ?: "").replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().trimEnd('.').let {
                val low = it.lowercase()
                if (low.endsWith(".zip") || low.endsWith(".cbz")) it.dropLast(4).trim() else it
            }

        fun uniqueName(name: String, taken: Set<String>): String {
            if (name !in taken) return name
            val base = name.substringBeforeLast('.', name)
            val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
            var i = 2
            while ("$base ($i)$ext" in taken) i++
            return "$base ($i)$ext"
        }

        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

        @Volatile private var instance: ArchiveDoctor? = null
        fun get(context: Context): ArchiveDoctor =
            instance ?: synchronized(this) { instance ?: ArchiveDoctor(context.applicationContext).also { instance = it } }
    }
}
