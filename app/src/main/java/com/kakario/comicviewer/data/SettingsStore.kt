package com.kakario.comicviewer.data

import android.content.Context

/** 화면 테마: 시스템 설정을 따르거나, 항상 밝게 / 항상 어둡게. */
enum class ThemeMode(val label: String) {
    SYSTEM("시스템 설정 따르기"),
    LIGHT("밝게 (흰색)"),
    DARK("어둡게 (검은색)"),
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = prefs.getString("theme", null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.SYSTEM
        set(value) {
            prefs.edit().putString("theme", value.name).apply()
        }

    /** 책장에서 한 줄에 보여줄 표지 개수 (2~6). */
    var gridColumns: Int
        get() = prefs.getInt("grid_columns", 3).coerceIn(2, 6)
        set(value) {
            prefs.edit().putInt("grid_columns", value.coerceIn(2, 6)).apply()
        }

    /** 넘기는 방향: true면 오른쪽→왼쪽 (일본 만화식). */
    var rightToLeft: Boolean
        get() = prefs.getBoolean("rtl", false)
        set(value) {
            prefs.edit().putBoolean("rtl", value).apply()
        }
}
