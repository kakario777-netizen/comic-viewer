package com.kakario.comicviewer.model

/** 화면에 보여줄 파일/폴더 하나 (FTP와 내 폰 공통). */
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
) {
    val isComicArchive: Boolean
        get() = !isDirectory && name.substringAfterLast('.', "").lowercase() in ARCHIVE_EXT

    private companion object {
        val ARCHIVE_EXT = setOf("zip", "cbz")
    }
}

/** 사용자에게 그대로 보여줄 한국어 오류. */
class UserFacingException(message: String) : Exception(message)

fun joinPath(parent: String, name: String): String =
    if (parent.endsWith("/")) parent + name else "$parent/$name"

fun parentPath(path: String): String {
    val trimmed = path.trimEnd('/')
    val idx = trimmed.lastIndexOf('/')
    return if (idx <= 0) "/" else trimmed.substring(0, idx)
}

/** 경로의 마지막 폴더 이름 ("/" 는 "최상위"). */
fun folderName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifBlank { "최상위" }

/** 폴더 먼저, 그다음 이름순(숫자는 숫자로) 정렬. */
fun List<FileEntry>.sortedForShelf(): List<FileEntry> =
    sortedWith(
        compareByDescending<FileEntry> { it.isDirectory }
            .thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) }
    )

/** "2권"이 "10권"보다 앞에 오도록 숫자를 숫자로 비교하는 정렬. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val ca = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val cb = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val r = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else {
                x.compareTo(y)
            }
            if (r != 0) return r
        }
        return ca.size - cb.size
    }
}
