package com.kakario.comicviewer.dup

import com.kakario.comicviewer.model.NaturalOrder
import com.kakario.comicviewer.thumb.ZipCover
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** 폴더 안 ZIP 하나의 기록 (숨김 파일에 저장되는 단위). */
data class BookPrint(
    val name: String,
    val size: Long,
    val modified: Long,
    /** 내용 지문 — 안의 이미지들의 CRC·크기로 만든 값. 이름이 달라도 내용이 같으면 같다. */
    val fingerprint: String?,
    val pages: Int,
)

/** 숨김 기록 파일 내용: 파일별 기록 + '무시'한 묶음들. */
data class DupIndex(val prints: Map<String, BookPrint>, val ignored: Set<String>)

/** 중복으로 묶인 파일들. */
data class DupGroup(val reason: Reason, val items: List<BookPrint>) {
    /** 묶음을 구별하는 값 — 무시 목록에 저장. 파일이 하나라도 새로 끼면 달라져서 다시 보인다. */
    val signature: String
        get() = reason.name + "|" + items.map { it.name }.sorted().joinToString("\u0001")

    enum class Reason(val label: String) {
        SAME_CONTENT("내용이 똑같은 파일"),
        SAME_VOLUME("같은 권으로 보이는 파일"),
    }
}

/** 폴더 목록과 기록을 비교한 결과. */
data class FolderDiff(val added: Int, val changed: Int, val removed: Int) {
    val hasNew: Boolean get() = added > 0 || changed > 0
}

object DupLogic {
    /** 폴더 안 숨김 기록 파일 이름 (점으로 시작해서 목록에 안 보임). */
    const val INDEX_FILE = ".comicviewer_dup.json"

    /** ZIP 목차로 내용 지문을 만든다 — 파일 전체를 받지 않고 목차만 있으면 됨. */
    fun fingerprint(entries: List<ZipCover.Entry>): Pair<String, Int> {
        val pages = ZipCover.imagePages(entries)
        val parts = pages.map { "${it.crc}:${it.size}" }.sorted()
        val md = MessageDigest.getInstance("SHA-1")
        parts.forEach { md.update(it.toByteArray()); md.update(0) }
        return md.digest().joinToString("") { "%02x".format(it) } to pages.size
    }

    fun diff(index: Map<String, BookPrint>, current: List<Triple<String, Long, Long>>): FolderDiff {
        var added = 0
        var changed = 0
        val names = HashSet<String>()
        for ((name, size, modified) in current) {
            names += name
            val old = index[name]
            when {
                old == null -> added++
                old.size != size -> changed++
                old.modified != 0L && modified != 0L && old.modified != modified -> changed++
            }
        }
        val removed = index.keys.count { it !in names }
        return FolderDiff(added, changed, removed)
    }

    /** 기록 하나가 지금 파일과 같아서 지문을 다시 만들 필요가 없는지. */
    fun upToDate(old: BookPrint?, size: Long, modified: Long): Boolean =
        old != null && old.fingerprint != null && old.size == size &&
            (old.modified == 0L || modified == 0L || old.modified == modified)

    fun group(prints: List<BookPrint>): List<DupGroup> {
        val result = mutableListOf<DupGroup>()

        // 1) 내용이 똑같은 파일
        val byContent = prints.filter { it.fingerprint != null && it.pages > 0 }
            .groupBy { it.fingerprint!! }
            .values.filter { it.size >= 2 }
        byContent.forEach { g -> result += DupGroup(DupGroup.Reason.SAME_CONTENT, g.sortedWith(byName)) }
        val contentSets = byContent.map { g -> g.map { it.name }.toSet() }

        // 2) 같은 제목·같은 권으로 보이는 파일 (화질판·재압축본 등)
        prints.groupBy { volumeKey(it.name) }
            .filterKeys { it != null }
            .values.filter { it.size >= 2 }
            .forEach { g ->
                val names = g.map { it.name }.toSet()
                // 이미 '내용이 똑같은 파일'로 전부 묶였으면 다시 보여주지 않음
                if (contentSets.none { it.containsAll(names) }) {
                    result += DupGroup(DupGroup.Reason.SAME_VOLUME, g.sortedWith(byName))
                }
            }
        return result
    }

    private val byName = Comparator<BookPrint> { a, b -> NaturalOrder.compare(a.name, b.name) }

    private val bracket = Regex("""[(\[{【「『<][^)\]}】」』>]*[)\]}】」』>]""")
    private val noise = Regex("""고화질|저화질|화질|완결|스캔본|스캔|디지털판|디지털|digital|scan|hq|lq""")
    private val volume = Regex("""(?:vol\.?|v|제)?\s*0*(\d{1,4})\s*(권|화|부|편|장)?""")

    /**
     * 이름에서 '제목 + 권수' 열쇠를 뽑는다. 예)
     *  "원피스 01권.zip", "원피스 1권 (고화질).zip", "[작가] 원피스 v1.zip" → "원피스#1권"
     * 숫자가 없으면 null (권수를 모르면 같은 권이라고 단정하지 않음).
     */
    fun volumeKey(fileName: String): String? {
        var s = fileName.substringBeforeLast('.').lowercase()
        s = bracket.replace(s, " ")
        s = noise.replace(s, " ")
        val m = volume.findAll(s).lastOrNull() ?: return null
        val title = s.substring(0, m.range.first).filter { it.isLetterOrDigit() }
        if (title.isEmpty()) return null
        val unit = m.groupValues[2].ifEmpty { "권" }
        return "$title#${m.groupValues[1].toInt()}$unit"
    }

    // ───── 기록 파일 읽기/쓰기 (JSON)

    fun decode(bytes: ByteArray?): DupIndex? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val arr = o.getJSONArray("files")
            val ign = o.optJSONArray("ignored")
            val ignored = if (ign == null) emptySet() else (0 until ign.length()).map { ign.getString(it) }.toSet()
            val prints = (0 until arr.length()).associate { i ->
                val f = arr.getJSONObject(i)
                val p = BookPrint(
                    name = f.getString("n"),
                    size = f.getLong("s"),
                    modified = f.optLong("m", 0),
                    fingerprint = f.optString("f").ifEmpty { null },
                    pages = f.optInt("p", 0),
                )
                p.name to p
            }
            DupIndex(prints, ignored)
        } catch (e: Exception) {
            null
        }
    }

    fun encode(prints: Collection<BookPrint>, ignored: Set<String> = emptySet()): ByteArray {
        val arr = JSONArray()
        prints.sortedWith(byName).forEach { p ->
            arr.put(
                JSONObject()
                    .put("n", p.name)
                    .put("s", p.size)
                    .put("m", p.modified)
                    .put("f", p.fingerprint ?: "")
                    .put("p", p.pages)
            )
        }
        return JSONObject()
            .put("app", "comic-viewer")
            .put("v", 1)
            .put("updated", System.currentTimeMillis())
            .put("files", arr)
            .put("ignored", JSONArray(ignored.sorted()))
            .toString()
            .toByteArray(Charsets.UTF_8)
    }
}
