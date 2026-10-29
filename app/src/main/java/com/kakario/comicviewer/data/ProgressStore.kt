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

    fun set(key: String, page: Int, total: Int) {
        prefs.edit().putInt(key, page).putInt("$key#total", total).apply()
    }

    fun clear(key: String) {
        prefs.edit().remove(key).remove("$key#total").apply()
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
