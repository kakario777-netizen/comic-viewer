package com.kakario.comicviewer.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.thumb.ThumbOrigin

/** 책 하나의 읽은 상태. page는 0부터, total은 전체 페이지 수(아직 안 열어봤으면 0). */
data class ReadProgress(val page: Int, val total: Int) {
    val started: Boolean get() = total > 0
    val finished: Boolean get() = total > 0 && page >= total - 1
    val fraction: Float get() = if (total > 0) (page + 1f) / total else 0f
    val percent: Int get() = (fraction * 100).toInt().coerceIn(0, 100)
}

/**
 * 책마다 마지막으로 본 페이지를 기억한다 (다시 열면 이어보기 + 책장에 진행률 표시).
 * 앱 전체에서 하나만 쓴다.
 */
class ProgressStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    /** 뷰어를 닫을 때마다 올라가는 번호 — 책장이 진행률을 다시 그리게 하는 신호. */
    var version by mutableIntStateOf(0)
        private set

    fun keyFor(origin: ThumbOrigin, entry: FileEntry): String =
        "${origin.tag}|${entry.path}|${entry.size}"

    fun get(key: String): ReadProgress =
        ReadProgress(prefs.getInt(key, 0), prefs.getInt("$key#total", 0))

    /** 마지막으로 읽은 시각(밀리초). 기록이 없거나 예전 기록이면 0. */
    fun time(key: String): Long = prefs.getLong("$key#time", 0L)

    fun set(key: String, page: Int, total: Int) {
        prefs.edit().putInt(key, page).putInt("$key#total", total)
            .putLong("$key#time", System.currentTimeMillis()).apply()
    }

    /** 서버(다른 기기)에서 받은 더 최근 기록으로 바꾼다. */
    fun setRemote(key: String, page: Int, total: Int, time: Long) {
        prefs.edit().putInt(key, page).putInt("$key#total", total).putLong("$key#time", time).apply()
    }

    data class Ref(val tag: String, val path: String, val size: Long, val key: String)

    /** 저장된 모든 기록 (FTP만). 키 모양: "ftp<id>|<경로>|<크기>" */
    fun allFtpKeys(): List<Ref> =
        prefs.all.keys.asSequence()
            .filter { !it.contains('#') && it.startsWith("ftp") }
            .mapNotNull { k ->
                val tag = k.substringBefore('|')
                val rest = k.substringAfter('|')
                val size = rest.substringAfterLast('|').toLongOrNull() ?: return@mapNotNull null
                Ref(tag, rest.substringBeforeLast('|'), size, k)
            }.toList()

    /** 이 폴더 바로 안의 책 기록들: (경로, 크기, 키) */
    fun keysIn(tag: String, folder: String): List<Triple<String, Long, String>> {
        val prefix = folder.trimEnd('/') + "/"
        return allFtpKeys().filter {
            it.tag == tag && it.path.startsWith(prefix) && !it.path.substring(prefix.length).contains('/')
        }.map { Triple(it.path, it.size, it.key) }
    }

    /** 이름을 바꿨을 때 읽은 위치를 새 이름으로 옮긴다. */
    fun move(from: String, to: String) {
        if (from == to || !prefs.contains(from)) return
        val p = get(from)
        prefs.edit().remove(from).remove("$from#total").remove("$from#time")
            .putInt(to, p.page).putInt("$to#total", p.total).putLong("$to#time", System.currentTimeMillis()).apply()
        version++
    }

    fun clear(key: String) {
        // 초기화도 '기록'으로 남긴다 (시각 포함) — 다른 기기에도 초기화가 퍼지게. 위치 0·전체 0 = 안 읽음
        prefs.edit().putInt(key, 0).putInt("$key#total", 0).putLong("$key#time", System.currentTimeMillis()).apply()
        version++
    }

    fun bump() {
        version++
    }

    companion object {
        @Volatile private var instance: ProgressStore? = null

        fun get(context: Context): ProgressStore =
            instance ?: synchronized(this) {
                instance ?: ProgressStore(context.applicationContext).also { instance = it }
            }
    }
}
