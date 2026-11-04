package com.kakario.comicviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 앱 전체 화면 위에 덮어 그리는 자리 (탭·제목줄까지 덮음).
 * 대화상자 창 대신 이걸 쓰면 상태바·내비게이션바 여백이 정확하게 맞는다.
 */
class OverlayHost {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
}

val LocalOverlayHost = compositionLocalOf<OverlayHost?> { null }
