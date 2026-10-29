package com.kakario.comicviewer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.thumb.ThumbOrigin
import com.kakario.comicviewer.thumb.ThumbnailLoader

/** 앱 어디서나 같은 썸네일 로더를 쓰도록 전달. */
val LocalThumbnails = compositionLocalOf<ThumbnailLoader?> { null }

/**
 * 표지 썸네일. 불러오는 동안·표지가 없을 때는 아이콘(📁/📦)을 보여준다.
 * 화면 밖으로 스크롤되면 불러오기를 자동으로 멈춘다.
 */
@Composable
fun CoverImage(origin: ThumbOrigin, entry: FileEntry, modifier: Modifier = Modifier) {
    val loader = LocalThumbnails.current
    val key = loader?.keyFor(origin, entry)
    val generation = loader?.generation ?: 0
    val bitmap by produceState<Bitmap?>(initialValue = key?.let { loader?.cached(it) }, key, generation) {
        if (loader == null || key == null) return@produceState
        // 다시 만들기 중엔 예전 표지를 그대로 보여주다가 새 표지가 나오면 바꾼다
        val fresh = loader.cached(key) ?: loader.load(origin, entry)
        if (fresh != null || loader.cached(key) == null) value = fresh
    }

    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                if (entry.isDirectory) "📁" else "📦",
                style = MaterialTheme.typography.displaySmall,
            )
        }
    }
}
