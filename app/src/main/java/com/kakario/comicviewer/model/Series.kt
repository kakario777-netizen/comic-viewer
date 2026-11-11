package com.kakario.comicviewer.model

/**
 * 같은 폴더 안 시리즈 묶기: 제목이 절반 넘게 같고 번호·소제목만 다른 책들을 이어서 표시.
 * 앞의 [번역]·[한글]·(작가) 같은 꼬리표는 비교에서 뺀다 (꼬리표만 같은 건 묶지 않음).
 */
data class SeriesMark(val label: String, val start: Boolean, val end: Boolean)

object Series {
    private val TAG = Regex("""[\[\(\{【「『<][^\]\)\}】」』>]*[\]\)\}】」』>]""")
    private val SPACES = Regex("""\s+""")
    private val TAIL = Regex("""[\s\d\-_.,#~+:;!·]*(권|화|부|편|vol\.?|ch\.?|part|ep\.?)?[\s\d\-_.,#~+:;!·]*$""", RegexOption.IGNORE_CASE)

    /** 비교용 제목: 확장자·꼬리표 빼고, 공백 하나로, 소문자로. */
    fun core(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName)
        return base.replace(TAG, " ").replace('_', ' ').replace(SPACES, " ").trim().lowercase()
    }

    private fun commonPrefix(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    /** 두 제목이 같은 시리즈인지: 다르고, 앞에서부터 절반 이상 같고, 같은 부분이 2글자 이상(숫자·기호 말고). */
    fun similar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty() || a == b) return false
        val cp = commonPrefix(a, b)
        if (cp * 2 < minOf(a.length, b.length)) return false
        val meaningful = a.substring(0, cp).replace(TAIL, "").trim()
        return meaningful.count { it.isLetter() } >= 2
    }

    /**
     * 보이는 순서대로 이웃끼리 비교해서 묶는다. 폴더가 끼면 끊긴다.
     * 반환: 경로 → 표시 정보 (묶인 것만).
     */
    fun group(entries: List<FileEntry>): Map<String, SeriesMark> {
        val out = HashMap<String, SeriesMark>()
        var i = 0
        while (i < entries.size) {
            if (entries[i].isDirectory) { i++; continue }
            var j = i
            while (j + 1 < entries.size && !entries[j + 1].isDirectory &&
                similar(core(entries[j].name), core(entries[j + 1].name))
            ) j++
            if (j > i) {
                val run = entries.subList(i, j + 1)
                val first = run.first().name.substringBeforeLast('.', run.first().name)
                    .replace(TAG, " ").replace('_', ' ').replace(SPACES, " ").trim()
                val cores = run.map { core(it.name) }
                val cp = cores.drop(1).fold(cores[0].length) { acc, c -> minOf(acc, commonPrefix(cores[0], c)) }
                val label = first.take(cp).replace(TAIL, "").trim().trimEnd('-', '_', '(', '[', ' ').ifEmpty { first }
                run.forEachIndexed { k, e -> out[e.path] = SeriesMark(label, k == 0, k == run.size - 1) }
            }
            i = j + 1
        }
        return out
    }
}
