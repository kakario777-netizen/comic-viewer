package com.kakario.comicviewer.thumb

import com.kakario.comicviewer.model.NaturalOrder
import com.kakario.comicviewer.model.RangeSource
import java.nio.charset.Charset
import java.util.zip.Inflater

/**
 * ZIP 파일 전체를 받지 않고 표지(첫 이미지)만 꺼낸다.
 *  1) 파일 끝부분을 읽어 목차(central directory) 위치를 찾고
 *  2) 목차에서 이름순 첫 이미지를 고른 뒤
 *  3) 그 이미지 부분만 읽어서 압축을 푼다.
 * FTP에서는 보통 2~3번의 작은 요청이면 끝난다.
 */
object ZipCover {

    private const val EOCD_SIG = 0x06054b50L
    private const val ZIP64_LOCATOR_SIG = 0x07064b50L
    private const val ZIP64_EOCD_SIG = 0x06064b50L
    private const val CEN_SIG = 0x02014b50L
    private const val LOC_SIG = 0x04034b50L

    private const val MAX_CEN_SIZE = 8 * 1024 * 1024
    private const val MAX_IMAGE_SIZE = 60 * 1024 * 1024

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif") // avif·heic: 안드로이드 12+ 기본 지원

    data class Entry(
        val name: String,
        val method: Int,
        val flags: Int,
        val compressedSize: Long,
        val size: Long,
        val localOffset: Long,
        /** 압축 전 데이터의 CRC32 — 중복 검사 지문에 쓴다. */
        val crc: Long = 0,
    )

    /** 표지 이미지의 원본 바이트. 이미지가 없거나 읽을 수 없는 ZIP이면 null. */
    fun firstImage(src: RangeSource): ByteArray? {
        val entries = listEntries(src) ?: return null
        val cover = pickCover(entries) ?: return null
        return extract(src, cover)
    }

    /** 만화 페이지로 쓸 이미지들을 이름순(숫자는 숫자로)으로. */
    fun imagePages(entries: List<Entry>): List<Entry> =
        entries
            .filter { e ->
                !e.name.endsWith("/") &&
                    !e.name.contains("__MACOSX") &&
                    !e.name.substringAfterLast('/').startsWith(".") &&
                    e.name.substringAfterLast('.', "").lowercase() in IMAGE_EXT &&
                    (e.flags and 1) == 0 // 암호 걸린 항목 제외
            }
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }

    fun pickCover(entries: List<Entry>): Entry? = imagePages(entries).firstOrNull()

    fun listEntries(src: RangeSource): List<Entry>? {
        val size = src.size
        if (size < 22) return null

        // 1) 끝부분에서 목차 끝 표시(EOCD) 찾기 — 뒤에 주석이 최대 65535바이트 붙을 수 있다.
        val tailLen = minOf(size, 22L + 65535L + 20L).toInt()
        val tailStart = size - tailLen
        val tail = src.read(tailStart, tailLen)
        var eocd = -1
        var i = tail.size - 22
        while (i >= 0) {
            if (u32(tail, i) == EOCD_SIG) {
                eocd = i
                break
            }
            i--
        }
        if (eocd < 0) return null

        var cenCount = u16(tail, eocd + 10).toLong()
        var cenSize = u32(tail, eocd + 12)
        var cenOffset = u32(tail, eocd + 16)

        // ZIP64 (4GB 넘는 파일이나 항목이 많은 파일)
        if (cenOffset == 0xFFFFFFFFL || cenSize == 0xFFFFFFFFL || cenCount == 0xFFFFL) {
            val loc = eocd - 20
            if (loc >= 0 && u32(tail, loc) == ZIP64_LOCATOR_SIG) {
                val z64Offset = u64(tail, loc + 8)
                val z64 = sliceOrRead(src, tail, tailStart, z64Offset, 56)
                if (z64.size >= 56 && u32(z64, 0) == ZIP64_EOCD_SIG) {
                    cenCount = u64(z64, 32)
                    cenSize = u64(z64, 40)
                    cenOffset = u64(z64, 48)
                }
            }
        }
        if (cenSize <= 0 || cenSize > MAX_CEN_SIZE || cenOffset < 0 || cenOffset + cenSize > size) return null

        // 2) 목차 읽기 (이미 읽은 끝부분에 들어 있으면 다시 받지 않음)
        val cen = sliceOrRead(src, tail, tailStart, cenOffset, cenSize.toInt())
        val result = ArrayList<Entry>(cenCount.coerceAtMost(10_000).toInt())
        var p = 0
        while (p + 46 <= cen.size && u32(cen, p) == CEN_SIG) {
            val flags = u16(cen, p + 8)
            val method = u16(cen, p + 10)
            var compSize = u32(cen, p + 20)
            var uncompSize = u32(cen, p + 24)
            val nameLen = u16(cen, p + 28)
            val extraLen = u16(cen, p + 30)
            val commentLen = u16(cen, p + 32)
            var localOffset = u32(cen, p + 42)
            if (p + 46 + nameLen + extraLen > cen.size) break

            val name = decodeName(cen, p + 46, nameLen, utf8 = (flags and 0x800) != 0)

            // ZIP64 확장 정보: 0xFFFFFFFF인 값만 순서대로 들어 있다.
            if (compSize == 0xFFFFFFFFL || uncompSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                var e = p + 46 + nameLen
                val end = e + extraLen
                while (e + 4 <= end) {
                    val id = u16(cen, e)
                    val len = u16(cen, e + 2)
                    if (id == 0x0001) {
                        var q = e + 4
                        if (uncompSize == 0xFFFFFFFFL && q + 8 <= e + 4 + len) { uncompSize = u64(cen, q); q += 8 }
                        if (compSize == 0xFFFFFFFFL && q + 8 <= e + 4 + len) { compSize = u64(cen, q); q += 8 }
                        if (localOffset == 0xFFFFFFFFL && q + 8 <= e + 4 + len) { localOffset = u64(cen, q) }
                        break
                    }
                    e += 4 + len
                }
            }

            result += Entry(name, method, flags, compSize, uncompSize, localOffset, u32(cen, p + 16))
            p += 46 + nameLen + extraLen + commentLen
        }
        return result
    }

    /** 항목 하나의 데이터를 읽어 압축을 푼다. */
    fun extract(src: RangeSource, entry: Entry): ByteArray? {
        if (entry.compressedSize > MAX_IMAGE_SIZE || entry.size > MAX_IMAGE_SIZE) return null
        if (entry.method != 0 && entry.method != 8) return null

        // 로컬 헤더(30바이트 + 이름 + 여분)와 데이터를 한 번에 읽는다. 여분 길이는 목차와 다를 수 있어 넉넉히.
        val guessHeader = 30 + entry.name.toByteArray().size + 256
        val wanted = (guessHeader + entry.compressedSize).coerceAtMost(src.size - entry.localOffset).toInt()
        var block = src.read(entry.localOffset, wanted)
        if (block.size < 30 || u32(block, 0) != LOC_SIG) return null
        val dataStart = 30 + u16(block, 26) + u16(block, 28)
        val dataEnd = dataStart + entry.compressedSize
        if (dataEnd > block.size) {
            // 여분 정보가 예상보다 길었던 경우 — 정확한 길이로 다시 읽기
            block = src.read(entry.localOffset, dataEnd.toInt())
            if (dataEnd > block.size) return null
        }
        val data = block.copyOfRange(dataStart, dataEnd.toInt())

        return when (entry.method) {
            0 -> data
            else -> inflate(data, entry.size)
        }
    }

    private fun inflate(data: ByteArray, expectedSize: Long): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(data)
            val out = ByteArray(if (expectedSize in 1..MAX_IMAGE_SIZE) expectedSize.toInt() else data.size * 2)
            var total = 0
            var buf = out
            while (!inflater.finished()) {
                if (total == buf.size) {
                    if (buf.size >= MAX_IMAGE_SIZE) return null
                    buf = buf.copyOf(minOf(buf.size * 2, MAX_IMAGE_SIZE))
                }
                val n = inflater.inflate(buf, total, buf.size - total)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                total += n
            }
            if (total == buf.size) buf else buf.copyOf(total)
        } catch (e: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun sliceOrRead(src: RangeSource, tail: ByteArray, tailStart: Long, offset: Long, length: Int): ByteArray =
        if (offset >= tailStart && offset + length <= tailStart + tail.size) {
            val from = (offset - tailStart).toInt()
            tail.copyOfRange(from, from + length)
        } else {
            src.read(offset, length)
        }

    private val koreanCharset: Charset = runCatching { Charset.forName("MS949") }
        .recoverCatching { Charset.forName("EUC-KR") }
        .getOrDefault(Charsets.ISO_8859_1)

    private fun decodeName(b: ByteArray, off: Int, len: Int, utf8: Boolean): String {
        if (utf8) return String(b, off, len, Charsets.UTF_8)
        // UTF-8 표시가 없어도 실제로는 UTF-8인 경우가 많아 먼저 시도
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(b, off, len)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(b, off, len, koreanCharset)
        }
    }

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)

    private fun u64(b: ByteArray, o: Int): Long =
        u32(b, o) or (u32(b, o + 4) shl 32)
}
