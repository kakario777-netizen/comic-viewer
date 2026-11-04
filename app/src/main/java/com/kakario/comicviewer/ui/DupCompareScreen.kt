package com.kakario.comicviewer.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.union
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kakario.comicviewer.dup.BookPrint
import com.kakario.comicviewer.dup.DupGroup
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.thumb.ThumbOrigin
import com.kakario.comicviewer.viewer.ComicBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Bg = Color(0xFF101114)
private val TitleBg = Color(0xCC000000)

/**
 * 중복 묶음 나란히 비교.
 *  - 세로 화면: 위아래로, 가로 화면·펼친 폴더블: 좌우로 같은 페이지를 동시에
 *  - 밀면 모두 같이 넘어가고, 아래 막대로 여러 장 한 번에 이동
 *  - 각 이미지 위에 제목·페이지·용량, 파일별 삭제(두 번 확인), 묶음 무시
 */
@Composable
fun DupCompareScreen(
    origin: ThumbOrigin,
    group: DupGroup,
    files: Map<String, FileEntry>,
    onDelete: suspend (BookPrint) -> Unit,
    onIgnore: () -> Unit,
    onClose: () -> Unit,
) {
    // 대화상자 창은 화면 가장자리(상태바·내비게이션바) 정보를 못 받는 폰이 있어서,
    // 앱 화면 쪽에서 읽은 값을 넘겨준다 → 아래 막대가 내비게이션바 위로 올라옴
    val outer = WindowInsets.safeDrawing.asPaddingValues()
    val outerInsets = WindowInsets(
        left = outer.calculateLeftPadding(LayoutDirection.Ltr),
        top = outer.calculateTopPadding(),
        right = outer.calculateRightPadding(LayoutDirection.Ltr),
        bottom = outer.calculateBottomPadding(),
    )
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompareContent(origin, group, files, onDelete, onIgnore, onClose, outerInsets)
    }
}

@Composable
private fun CompareContent(
    origin: ThumbOrigin,
    group: DupGroup,
    files: Map<String, FileEntry>,
    onDelete: suspend (BookPrint) -> Unit,
    onIgnore: () -> Unit,
    onClose: () -> Unit,
    outerInsets: WindowInsets,
) {
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()

    // 아직 남아 있는 파일 (지우면 빠짐)
    val items = remember { mutableStateListOf<BookPrint>().apply { addAll(group.items.filter { it.name in files }) } }
    // 화면에 띄울 파일 — 기본 앞의 2개, 3개 이상인 묶음은 위쪽 이름표로 골라서 최대 3개
    val shown = remember { mutableStateListOf<String>().apply { addAll(items.take(2).map { it.name }) } }
    var deleting by remember { mutableStateOf<Pair<BookPrint, Int>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 비교용 책 — 파일마다 하나씩 (FTP 연결 1개씩)
    val books = remember { mutableStateMapOf<String, ComicBook>() }
    val counts = remember { mutableStateMapOf<String, Int>() }
    val errors = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(shown.toList()) {
        shown.forEach { name ->
            if (name !in books) files[name]?.let { books[name] = ComicBook(origin, it, maxSide = 1600, connections = 1) }
        }
    }
    DisposableEffect(Unit) { onDispose { books.values.forEach { it.close() } } }
    shown.forEach { name ->
        key(name) {
            val book = books[name]
            LaunchedEffect(book) {
                if (book == null || name in counts) return@LaunchedEffect
                try {
                    counts[name] = book.open()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors[name] = e.message ?: "열 수 없어요"
                }
            }
        }
    }

    val maxPages = shown.maxOfOrNull { counts[it] ?: 0 }?.coerceAtLeast(1) ?: 1
    val pager = rememberPagerState { maxPages }
    val config = LocalConfiguration.current
    val sideBySide = config.screenWidthDp > config.screenHeightDp

    // 다음 몇 장 미리 받기
    LaunchedEffect(pager.currentPage, shown.toList(), counts.toMap()) {
        val p = pager.currentPage
        shown.forEach { n -> books[n]?.let { b -> for (i in p + 1..p + 3) b.prefetch(i) } }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            // 둘 중 큰 쪽 — 대화상자가 직접 받은 값과 앱 화면에서 읽은 값
            .windowInsetsPadding(WindowInsets.safeDrawing.union(outerInsets))
    ) {
        // ── 위: 닫기 · 묶음 종류 · 무시
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기", tint = Color.White)
            }
            Text(
                group.reason.label,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onIgnore) { Text("이 묶음 무시", color = Color.White) }
        }

        // 3개 이상이면 비교할 파일 고르기 (최대 3개)
        if (items.size > 2) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEachIndexed { i, b ->
                    val on = b.name in shown
                    Text(
                        "${i + 1}. ${b.name}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        modifier = Modifier
                            .width(150.dp)
                            .clip(RoundedCornerShape(50))
                            .then(
                                if (on) Modifier.background(MaterialTheme.colorScheme.primary)
                                else Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(50))
                            )
                            .clickable {
                                if (on && shown.size > 1) shown.remove(b.name)
                                else if (!on) {
                                    if (shown.size >= 3) shown.removeAt(0)
                                    shown.add(b.name)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // ── 가운데: 같은 페이지를 동시에 — 밀면 모두 같이 넘어감
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            val panes: @Composable (Modifier) -> Unit = { m ->
                shown.forEach { name ->
                    val b = items.firstOrNull { it.name == name } ?: return@forEach
                    key(name) {
                        Pane(
                            print = b,
                            book = books[name],
                            page = page,
                            count = counts[name],
                            error = errors[name],
                            onDelete = { deleting = b to 1 },
                            modifier = m,
                        )
                    }
                }
            }
            if (sideBySide) {
                Row(Modifier.fillMaxSize().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    panes(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    panes(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }

        // ── 아래: 여러 장 한 번에 넘기는 막대
        var dragging by remember { mutableStateOf<Float?>(null) }
        Row(
            // 내비게이션바와 붙지 않게 아래로 여유를 더 둔다
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${dragging?.roundToInt() ?: (pager.currentPage + 1)} / $maxPages",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(72.dp),
            )
            PageSlider(
                value = dragging ?: (pager.currentPage + 1).toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { v -> scope.launch { pager.scrollToPage(v.roundToInt() - 1) } }
                    dragging = null
                },
                valueRange = 1f..maxPages.coerceAtLeast(2).toFloat(),
                modifier = Modifier.weight(1f),
            )
        }
        message?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
    }

    deleting?.let { (b, step) ->
        DeleteTwiceDialog(
            name = b.name,
            size = b.size,
            step = step,
            onNext = { deleting = b to 2 },
            onCancel = { deleting = null },
            onConfirm = {
                deleting = null
                if (busy) return@DeleteTwiceDialog
                busy = true
                scope.launch {
                    try {
                        books.remove(b.name)?.close()
                        onDelete(b)
                        items.removeAll { it.name == b.name }
                        shown.remove(b.name)
                        items.firstOrNull { it.name !in shown }?.let { if (shown.size < 2) shown.add(it.name) }
                        message = "'${b.name}' 삭제했어요"
                        if (items.size < 2) onClose()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = e.message ?: "삭제하지 못했어요"
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }
}

/** 비교 한 칸: 위에 제목줄, 아래에 그 책의 같은 페이지. */
@Composable
private fun Pane(
    print: BookPrint,
    book: ComicBook?,
    page: Int,
    count: Int?,
    error: String?,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().background(TitleBg).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                Text(
                    print.name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${if (count != null) "${(page + 1).coerceAtMost(count)}/${count}쪽" else "…"} · ${sizeText(print.size)}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = onDelete) { Text("삭제", color = MaterialTheme.colorScheme.error) }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                error != null -> Text(error, color = Color.White, style = MaterialTheme.typography.bodySmall)
                book == null || count == null -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
                page >= count -> Text("이 파일엔 ${page + 1}쪽이 없어요", color = Color.White.copy(alpha = 0.6f))
                else -> {
                    val bmp by produceState<Bitmap?>(book.cached(page), book, page) {
                        if (value == null) value = runCatching { book.page(page) }.getOrNull()
                    }
                    val b = bmp
                    if (b == null) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
                    } else {
                        Image(
                            bitmap = b.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}
