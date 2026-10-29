package com.kakario.comicviewer.data

import android.content.Context

/** 책마다 마지막으로 본 페이지를 기억한다 (다시 열면 이어보기). */
class ProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun get(bookKey: String): Int = prefs.getInt(bookKey, 0)

    fun set(bookKey: String, page: Int) {
        prefs.edit().putInt(bookKey, page).apply()
    }
}
