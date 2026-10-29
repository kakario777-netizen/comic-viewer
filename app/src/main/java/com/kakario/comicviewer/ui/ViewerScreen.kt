package com.kakario.comicviewer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kakario.comicviewer.data.ProgressStore
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.UserFacingException
import com.kakario.comicviewer.thumb.ThumbOrigin
import com.kakario.comicviewer.viewer.ComicBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val OverlayBg = Color(0xCC000000)

/** ZIP 만화 보기 화면 (전체화면). */
@Composable
fun ViewerScreen(
    origin: ThumbOrigin,
    entry: FileEntry,
    rightToLeft: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val maxSide = remember {
        val dm = context.resources.displayMetrics
        (maxOf(dm.widthPixels, dm.heightPixels) * 1.5f).roundToInt().coerceIn(1600, 3000)
    }
    val book = remember(origin, entry.path) { ComicBook(origin, entry, maxSide) }
    DisposableEffect(book) { onDispose { book.close() } }

    var pageCount by remember { mutableStateOf<Int?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(book, retry) {
        openError = null
        try {
            pageCount = book.open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: UserFacingException) {
            openError = e.message
        } catch (e: Exception) {
            openError = "열 수 없어요: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    ImmersiveMode()
    BackHandler(onBack = onClose)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val n = pageCount
        val err = openError
        when {
            err != null -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(err, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row {
                    Button(onClick = { retry++ }) { Text("다시 시도") }
                    Spacer(Modifier.padding(8.dp))
                    Button(onClick = onClose) { Text("닫기") }
                }
            }

            n == null -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(entry.name, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            else -> BookPager(origin, entry, book, n, rightToLeft, onClose)
        }
    }
}

@Composable
private fun BookPager(
    origin: ThumbOrigin,
    entry: FileEntry,
    book: ComicBook,
    pageCount: Int,
    rightToLeft: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val progress = remember { ProgressStore(context) }
    val bookKey = "${origin.tag}|${entry.path}|${entry.size}"
    val startPage = remember { progress.get(bookKey).coerceIn(0, pageCount - 1) }
    val pagerState = rememberPagerState(initialPage = startPage) { pageCount }
    val scope = rememberCoroutineScope()

    var zoomed by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(false) }

    // 현재 페이지 저장 + 앞으로 2장 미리 읽기
    LaunchedEffect(pagerState.currentPage) {
        val p = pagerState.currentPage
        progress.set(bookKey, p)
        book.prefetch(p + 1)
        book.prefetch(p + 2)
        book.prefetch(p - 1)
        zoomed = false
    }

    fun goTo(page: Int) {
        if (page !in 0 until pageCount) return
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            reverseLayout = rightToLeft,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            key = { it },
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            PageView(
                book = book,
                index = index,
                onZoomChange = { if (index == pagerState.currentPage) zoomed = it },
                onTap = { x, width ->
                    when {
                        x < width / 3f -> goTo(if (rightToLeft) pagerState.currentPage + 1 else pagerState.currentPage - 1)
                        x > width * 2f / 3f -> goTo(if (rightToLeft) pagerState.currentPage - 1 else pagerState.currentPage + 1)
                        else -> overlay = !overlay
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = overlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(OverlayBg)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기", tint = Color.White)
                }
                Text(
                    entry.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        AnimatedVisibility(
            visible = overlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(OverlayBg)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                val shown = dragging?.roundToInt() ?: (pagerState.currentPage + 1)
                Text("$shown / $pageCount", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = dragging ?: (pagerState.currentPage + 1).toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { v -> scope.launch { pagerState.scrollToPage(v.roundToInt() - 1) } }
                        dragging = null
                    },
                    valueRange = 1f..pageCount.coerceAtLeast(2).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                    // 오른쪽→왼쪽으로 읽을 때는 슬라이더도 뒤집는다
                    modifier = Modifier.graphicsLayer { scaleX = if (rightToLeft) -1f else 1f },
                )
            }
        }
    }
}

/** 페이지 한 장: 불러오기, 핀치/더블탭 확대, 탭 영역 넘기기. */
@Composable
private fun PageView(
    book: ComicBook,
    index: Int,
    onZoomChange: (Boolean) -> Unit,
    onTap: (x: Float, width: Float) -> Unit,
) {
    var reload by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val bitmap by produceState<Bitmap?>(book.cached(index), index, reload) {
        if (value != null) return@produceState
        error = null
        try {
            value = book.page(index)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UserFacingException) {
            error = e.message
        } catch (e: Exception) {
            error = "페이지를 불러오지 못했어요: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        if (s <= 1f) return Offset.Zero
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(index) {
                detectTapGestures(
                    onTap = { pos -> if (scale <= 1f) onTap(pos.x, size.width.toFloat()) },
                    onDoubleTap = { pos ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                            onZoomChange(false)
                        } else {
                            val s = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = s
                            offset = clamp((pos - center) * (1f - s), s)
                            onZoomChange(true)
                        }
                    },
                )
            }
            .pointerInput(index) {
                // 두 손가락이면 확대/축소, 확대된 상태면 한 손가락으로 이동.
                // 확대 안 된 상태의 한 손가락 밀기는 건드리지 않아서 페이지 넘기기로 간다.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            var newOffset = if (centroid.isUsable()) {
                                centroid - center - (centroid - center - offset) * (newScale / scale)
                            } else {
                                offset
                            }
                            newOffset += pan
                            scale = newScale
                            offset = clamp(newOffset, newScale)
                            onZoomChange(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        val err = error
        when {
            b != null -> Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )

            err != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp),
            ) {
                Text(err, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { reload++ }) { Text("다시 시도") }
            }

            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.height(8.dp))
                Text("${index + 1}", color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

private fun Offset.isUsable(): Boolean = this != Offset.Unspecified && !x.isNaN() && !y.isNaN()

/** 보는 동안 상태바·내비게이션바를 숨기고 화면이 꺼지지 않게 한다. */
@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
