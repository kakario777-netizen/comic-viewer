package com.kakario.comicviewer.viewer

import android.view.KeyEvent

/**
 * 하드웨어 버튼(볼륨 버튼, 블루투스 페이지 터너)으로 페이지 넘기기.
 * 뷰어가 열려 있는 동안 handler를 등록하고, MainActivity가 버튼 입력을 여기로 먼저 보낸다.
 */
object PageKeys {
    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null

    /** +1 = 다음 장, -1 = 이전 장, 0 = 넘기기와 상관없는 버튼. */
    fun direction(keyCode: Int, rightToLeft: Boolean, volumeKeys: Boolean): Int = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeys) 1 else 0
        KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeys) -1 else 0

        // 페이지 터너가 주로 보내는 버튼들
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 1

        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_REWIND -> -1

        // 좌우 방향키는 넘기는 방향을 따른다
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (rightToLeft) -1 else 1
        KeyEvent.KEYCODE_DPAD_LEFT -> if (rightToLeft) 1 else -1

        else -> 0
    }
}
