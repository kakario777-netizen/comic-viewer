package com.kakario.comicviewer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.view.KeyEvent
import androidx.compose.runtime.rememberUpdatedState
import com.kakario.comicviewer.viewer.PageKeys
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kakario.comicviewer.data.PageLayout
import com.kakario.comicviewer.data.ProgressStore
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.UserFacingException
import com.kakario.comicviewer.thumb.ThumbOrigin
import com.kakario.comicviewer.viewer.ComicBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val OverlayBg = Color(0xCC000000)

/** 뷰어에서 바꿀 수 있는 보기 설정 묶음 (설정 화면과 같은 값). */
data class ViewerPrefs(
    val pageLayout: PageLayout,
    val rightToLeft: Boolean,
    /** 화면 아래 시간·남은 페이지 표시 */
    val showInfoBar: Boolean = true,
    /** 볼륨 버튼으로 넘기기 */
    val volumeKeys: Boolean = true,
    /** FTP 동시 연결 수 */
    val connections: Int = 3,
    /** 미리 받아둘 페이지 수 */
    val prefetchPages: Int = 6,
)

/** ZIP 만화 보기 화면 (전체화면). */
@Composable
fun ViewerScreen(
    origin: ThumbOrigin,
    entry: FileEntry,
    nextEntry: FileEntry?,
    prefs: ViewerPrefs,
    onPrefsChange: (ViewerPrefs) -> Unit,
    onOpenNext: (FileEntry) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val maxSide = remember {
        val dm = context.resources.displayMetrics
        (maxOf(dm.widthPixels, dm.heightPixels) * 1.5f).roundToInt().coerceIn(1600, 3000)
    }
    val progress = remember { ProgressStore.get(context) }
    val book = remember(origin, entry.path) { ComicBook(origin, entry, maxSide, prefs.connections) }
    DisposableEffect(book) {
        onDispose {
            book.close()
            progress.bump() // 책장 진행률 다시 그리기
        }
    }

    var pageCount by remember(book) { mutableStateOf<Int?>(null) }
    var openError by remember(book) { mutableStateOf<String?>(null) }
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
                    Spacer(Modifier.width(12.dp))
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

            // 다음 권으로 넘어가면 페이지 상태를 새로 시작하도록 책마다 따로 둔다
            else -> key(entry.path) {
              BookPager(
                bookKey = progress.keyFor(origin, entry),
                entry = entry,
                book = book,
                pageCount = n,
                nextEntry = nextEntry,
                prefs = prefs,
                onPrefsChange = onPrefsChange,
                onOpenNext = onOpenNext,
                onClose = onClose,
              )
            }
        }
    }
}

/** 한 화면에 보여줄 페이지 묶음 만들기. 두 장 보기는 표지만 따로, 그다음부터 2장씩. */
private fun buildSpreads(pageCount: Int, double: Boolean): List<List<Int>> {
    if (!double) return (0 until pageCount).map { listOf(it) }
    val result = mutableListOf(listOf(0))
    var i = 1
    while (i < pageCount) {
        result += if (i + 1 < pageCount) listOf(i, i + 1) else listOf(i)
        i += 2
    }
    return result
}

@Composable
private fun BookPager(
    bookKey: String,
    entry: FileEntry,
    book: ComicBook,
    pageCount: Int,
    nextEntry: FileEntry?,
    prefs: ViewerPrefs,
    onPrefsChange: (ViewerPrefs) -> Unit,
    onOpenNext: (FileEntry) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val progress = remember { ProgressStore.get(context) }
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val double = when (prefs.pageLayout) {
        PageLayout.SINGLE -> false
        PageLayout.DOUBLE -> true
        PageLayout.AUTO -> landscape
    }
    val rtl = prefs.rightToLeft

    // 지금 보고 있는 페이지 번호 — 한 장/두 장을 바꾸거나 화면을 돌려도 이 페이지를 유지한다.
    var currentPage by remember {
        mutableIntStateOf(progress.get(bookKey).page.coerceIn(0, pageCount - 1))
    }
    var overlay by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    key(double) {
        val spreads = remember(pageCount) { buildSpreads(pageCount, double) }
        val endIndex = spreads.size // 마지막 장 다음의 '다 읽었어요' 화면
        val pagerState = rememberPagerState(
            initialPage = spreads.indexOfFirst { currentPage in it }.coerceAtLeast(0)
        ) { spreads.size + 1 }
        var zoomed by remember { mutableStateOf(false) }

        LaunchedEffect(pagerState.currentPage) {
            val s = pagerState.currentPage
            zoomed = false
            if (s < spreads.size) {
                currentPage = spreads[s].first()
                val last = spreads[s].last()
                progress.set(bookKey, last, pageCount)
                // 바로 다음 화면은 그림까지 준비, 그 뒤 페이지들은 원본을 미리 받아둔다 (여러 연결로 동시에)
                spreads.getOrNull(s + 1)?.forEach { book.prepare(it) }
                for (i in last + 1..last + prefs.prefetchPages) book.prefetch(i)
                spreads.getOrNull(s - 1)?.forEach { book.prefetch(it) }
            } else {
                progress.set(bookKey, pageCount - 1, pageCount)
            }
        }

        fun goToSpread(index: Int) {
            if (index !in 0..endIndex) return
            scope.launch { pagerState.animateScrollToPage(index) }
        }

        // 볼륨 버튼 / 페이지 터너
        val goTo by rememberUpdatedState<(Int) -> Unit>({ goToSpread(it) })
        val rtlNow by rememberUpdatedState(rtl)
        val volumeNow by rememberUpdatedState(prefs.volumeKeys)
        DisposableEffect(pagerState) {
            val handler: (KeyEvent) -> Boolean = { e ->
                val dir = PageKeys.direction(e.keyCode, rtlNow, volumeNow)
                if (dir == 0) {
                    false
                } else {
                    if (e.action == KeyEvent.ACTION_DOWN) {
                        // 버튼 하나는 한 장씩 (꾹 누르고 있으면 계속 넘어감)
                        goTo(pagerState.currentPage + dir)
                    }
                    true
                }
            }
            PageKeys.handler = handler
            onDispose { if (PageKeys.handler === handler) PageKeys.handler = null }
        }

        Box(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                reverseLayout = rtl,
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                key = { it },
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                if (index == endIndex) {
                    EndCard(
                        entry = entry,
                        nextEntry = nextEntry,
                        onOpenNext = onOpenNext,
                        onClose = onClose,
                        onBack = { goToSpread(endIndex - 1) },
                    )
                } else {
                    SpreadView(
                        book = book,
                        pages = spreads[index],
                        rightToLeft = rtl,
                        onZoomChange = { if (index == pagerState.currentPage) zoomed = it },
                        onTap = { x, width ->
                            val forward = pagerState.currentPage + 1
                            val backward = pagerState.currentPage - 1
                            when {
                                x < width / 3f -> goToSpread(if (rtl) forward else backward)
                                x > width * 2f / 3f -> goToSpread(if (rtl) backward else forward)
                                else -> overlay = !overlay
                            }
                        },
                    )
                }
            }

            if (prefs.showInfoBar && !overlay && pagerState.currentPage < endIndex) {
                InfoBar(
                    lastShown = spreads[pagerState.currentPage].last(),
                    pageCount = pageCount,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            ViewerOverlay(
                visible = overlay && pagerState.currentPage < endIndex,
                title = entry.name,
                spread = spreads.getOrNull(pagerState.currentPage) ?: listOf(pageCount - 1),
                pageCount = pageCount,
                prefs = prefs,
                onPrefsChange = onPrefsChange,
                onJump = { page ->
                    scope.launch { pagerState.scrollToPage(spreads.indexOfFirst { page in it }.coerceAtLeast(0)) }
                },
                onClose = onClose,
            )
        }
    }
}

/** 가운데를 탭하면 나오는 위·아래 메뉴. */
@Composable
private fun ViewerOverlay(
    visible: Boolean,
    title: String,
    spread: List<Int>,
    pageCount: Int,
    prefs: ViewerPrefs,
    onPrefsChange: (ViewerPrefs) -> Unit,
    onJump: (page: Int) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
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
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(OverlayBg)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                val label = dragging?.let { "${it.roundToInt()}" }
                    ?: spread.joinToString("-") { "${it + 1}" }
                Text("$label / $pageCount", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = dragging ?: (spread.first() + 1).toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { onJump(it.roundToInt() - 1) }
                        dragging = null
                    },
                    valueRange = 1f..pageCount.coerceAtLeast(2).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                    // 오른쪽→왼쪽으로 읽을 때는 막대도 뒤집는다
                    modifier = Modifier.graphicsLayer { scaleX = if (prefs.rightToLeft) -1f else 1f },
                )

                // 보기 방식: 한 장 / 두 장 / 자동
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PageLayout.entries.forEach { layout ->
                        Chip(
                            text = layout.short,
                            selected = prefs.pageLayout == layout,
                            onClick = { onPrefsChange(prefs.copy(pageLayout = layout)) },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Chip(
                        text = if (prefs.rightToLeft) "← 오른쪽부터" else "왼쪽부터 →",
                        selected = false,
                        onClick = { onPrefsChange(prefs.copy(rightToLeft = !prefs.rightToLeft)) },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** 화면 아래 흐린 작은 글씨: 왼쪽 시간, 오른쪽 남은 페이지와 읽은 %. */
@Composable
private fun InfoBar(lastShown: Int, pageCount: Int, modifier: Modifier) {
    val context = LocalContext.current
    val format = remember {
        SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "HH:mm" else "a h:mm", Locale.KOREA)
    }
    val time by produceState(format.format(Date())) {
        while (true) {
            value = format.format(Date())
            // 다음 '분'이 바뀌는 순간까지 기다렸다가 갱신
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    val remaining = (pageCount - 1 - lastShown).coerceAtLeast(0)
    val percent = ((lastShown + 1) * 100 / pageCount.coerceAtLeast(1)).coerceIn(0, 100)
    val style = MaterialTheme.typography.labelSmall
    val color = Color(0x99A0A0A0)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(time, color = color, style = style)
        Text(
            if (remaining == 0) "마지막 페이지 · $percent%" else "남은 ${remaining}쪽 · $percent%",
            color = color,
            style = style,
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.8f),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(shape)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary, shape)
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), shape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** 마지막 장 다음 화면: 다음 권 열기 / 닫기. */
@Composable
private fun EndCard(
    entry: FileEntry,
    nextEntry: FileEntry?,
    onOpenNext: (FileEntry) -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("다 읽었어요 📖", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                entry.name,
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            if (nextEntry != null) {
                Text("다음 권", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    nextEntry.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { onOpenNext(nextEntry) }) { Text("다음 권 보기") }
            } else {
                Text("이 폴더의 마지막 권이에요", color = Color.White.copy(alpha = 0.8f))
            }
            Spacer(Modifier.height(12.dp))
            Row {
                OutlinedButton(onClick = onBack) { Text("마지막 장으로", color = Color.White) }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onClose) { Text("책장으로", color = Color.White) }
            }
        }
    }
}

/** 한 화면(한 장 또는 두 장): 불러오기, 핀치/더블탭 확대, 탭 영역 넘기기. */
@Composable
private fun SpreadView(
    book: ComicBook,
    pages: List<Int>,
    rightToLeft: Boolean,
    onZoomChange: (Boolean) -> Unit,
    onTap: (x: Float, width: Float) -> Unit,
) {
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
            .pointerInput(pages) {
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
            .pointerInput(pages) {
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
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            if (pages.size == 1) {
                PageImage(book, pages[0], Alignment.Center, Modifier.weight(1f))
            } else {
                // 두 장: 왼→오로 읽으면 앞 페이지가 왼쪽, 오→왼이면 앞 페이지가 오른쪽.
                // 가운데로 붙여서 펼친 책처럼 보이게 한다.
                val (left, right) = if (rightToLeft) pages[1] to pages[0] else pages[0] to pages[1]
                PageImage(book, left, Alignment.CenterEnd, Modifier.weight(1f))
                PageImage(book, right, Alignment.CenterStart, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PageImage(book: ComicBook, index: Int, alignment: Alignment, modifier: Modifier) {
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

    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        val b = bitmap
        val err = error
        when {
            b != null -> Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = alignment,
                modifier = Modifier.fillMaxSize(),
            )

            err != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
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
