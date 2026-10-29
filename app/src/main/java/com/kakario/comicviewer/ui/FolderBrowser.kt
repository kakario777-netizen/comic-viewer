package com.kakario.comicviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakario.comicviewer.data.ProgressStore
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.UserFacingException
import com.kakario.comicviewer.thumb.ThumbOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface LoadState {
    data object Loading : LoadState
    data class Loaded(val entries: List<FileEntry>) : LoadState
    data class Failed(val message: String) : LoadState
}

/**
 * FTP와 내 폰이 함께 쓰는 책장(폴더 탐색) 화면.
 * 지금 보고 있는 폴더(path)는 바깥에서 관리해서, 탭을 옮겨 다녀도 위치가 유지된다.
 */
@Composable
fun FolderBrowser(
    title: String,
    path: String,
    origin: ThumbOrigin,
    load: suspend (String) -> List<FileEntry>,
    onUp: () -> Unit,
    onOpenDir: (String) -> Unit,
    /** ZIP을 열 때: 누른 책과, 같은 폴더의 책 목록(다음 권 찾기용). */
    onOpenArchive: (entry: FileEntry, siblings: List<FileEntry>) -> Unit,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (path: String) -> Unit,
    /** 파일 삭제 (길게 누르기 메뉴). */
    delete: suspend (FileEntry) -> Unit,
) {
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    val progressStore = remember { ProgressStore.get(context) }
    // 길게 누르기 메뉴 / 두 번 확인
    var menuFor by remember { mutableStateOf<FileEntry?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    val currentLoad by rememberUpdatedState(load)
    val currentDelete by rememberUpdatedState(delete)
    LaunchedEffect(path, reloadKey) {
        state = LoadState.Loading
        state = try {
            LoadState.Loaded(currentLoad(path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: UserFacingException) {
            LoadState.Failed(e.message ?: "알 수 없는 오류")
        } catch (e: Exception) {
            LoadState.Failed("예상치 못한 오류: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    BackHandler(onBack = onUp)

    fun toast(msg: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    fun toggle(p: String) {
        val wasOn = isFavorite(p)
        onToggleFavorite(p)
        toast(if (wasOn) "즐겨찾기에서 뺐어요" else "즐겨찾기에 추가했어요 — 첫 탭에서 바로 열 수 있어요")
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BrowserHeader(
                title = title,
                subtitle = path,
                onBack = onUp,
                actions = {
                    StarButton(on = isFavorite(path), onClick = { toggle(path) })
                    IconButton(onClick = { reloadKey++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "새로고침")
                    }
                },
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val s = state) {
                    LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    is LoadState.Failed -> ErrorView(
                        message = s.message,
                        onRetry = { reloadKey++ },
                        modifier = Modifier.align(Alignment.Center),
                    )

                    is LoadState.Loaded -> {
                        val visible = s.entries.filter { it.isDirectory || it.isComicArchive }
                        if (visible.isEmpty()) {
                            Text(
                                "이 폴더에는 하위 폴더나 ZIP 파일이 없어요",
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(LocalGridColumns.current),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(visible, key = { it.path }) { entry ->
                                    ShelfTile(
                                        origin = origin,
                                        entry = entry,
                                        favorite = entry.isDirectory && isFavorite(entry.path),
                                        onToggleFavorite = { toggle(entry.path) },
                                        onClick = {
                                            if (entry.isDirectory) onOpenDir(entry.path)
                                            else onOpenArchive(entry, visible.filter { it.isComicArchive })
                                        },
                                        onLongClick = { if (entry.isComicArchive) menuFor = entry },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    // ── 길게 누르기 메뉴
    menuFor?.let { target ->
        val started = remember(target, progressStore.version) {
            progressStore.get(progressStore.keyFor(origin, target)).started
        }
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(target.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    MenuItem("📖  읽은 진행률 초기화", enabled = started) {
                        menuFor = null
                        confirm = Confirm(target, ConfirmAction.RESET, 1)
                    }
                    MenuItem("🗑️  파일 삭제", color = MaterialTheme.colorScheme.error) {
                        menuFor = null
                        confirm = Confirm(target, ConfirmAction.DELETE, 1)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuFor = null }) { Text("닫기") } },
        )
    }

    // ── 두 번 확인
    confirm?.let { c ->
        val isDelete = c.action == ConfirmAction.DELETE
        val title = when {
            isDelete && c.step == 1 -> "파일을 삭제할까요?"
            isDelete -> "정말 삭제할까요?"
            c.step == 1 -> "진행률을 초기화할까요?"
            else -> "정말 초기화할까요?"
        }
        val body = when {
            isDelete && c.step == 1 -> "'${c.entry.name}'\n파일을 ${if (origin.server != null) "서버" else "폰"}에서 지워요."
            isDelete -> "삭제한 파일은 되돌릴 수 없어요."
            c.step == 1 -> "'${c.entry.name}'\n읽은 위치 기록을 지우고 처음부터 보게 돼요."
            else -> "읽던 페이지 기록이 사라져요."
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    if (c.step == 1) {
                        confirm = c.copy(step = 2)
                        return@TextButton
                    }
                    confirm = null
                    val key = progressStore.keyFor(origin, c.entry)
                    if (isDelete) {
                        scope.launch {
                            try {
                                currentDelete(c.entry)
                                progressStore.clear(key)
                                toast("삭제했어요")
                                reloadKey++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: UserFacingException) {
                                toast(e.message ?: "삭제하지 못했어요")
                            } catch (e: Exception) {
                                toast("삭제하지 못했어요: ${e.message ?: e.javaClass.simpleName}")
                            }
                        }
                    } else {
                        progressStore.clear(key)
                        toast("진행률을 초기화했어요")
                    }
                }) {
                    Text(
                        when {
                            c.step == 1 -> "다음"
                            isDelete -> "네, 삭제할게요"
                            else -> "네, 초기화할게요"
                        },
                        color = if (c.step == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } },
        )
    }
}

private enum class ConfirmAction { DELETE, RESET }
private data class Confirm(val entry: FileEntry, val action: ConfirmAction, val step: Int)

@Composable
private fun MenuItem(
    text: String,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = if (enabled) color else color.copy(alpha = 0.35f),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
/** 책장의 한 칸: 표지 + 이름. 폴더는 왼쪽 아래 📁 표시와 오른쪽 위 즐겨찾기 별. */
@Composable
private fun ShelfTile(
    origin: ThumbOrigin,
    entry: FileEntry,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box {
            CoverImage(
                origin = origin,
                entry = entry,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(8.dp)),
            )
            if (entry.isComicArchive) {
                ReadBadge(origin, entry, Modifier.align(Alignment.BottomCenter))
            }
            if (entry.isDirectory) {
                Text(
                    "📁",
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Badge, RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Badge)
                        .clickable(onClick = onToggleFavorite),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (favorite) "★" else "☆",
                        color = if (favorite) StarColor else Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** 표지 아래쪽: 읽은 만큼 빨간 막대, 다 읽었으면 '다 읽음' 표시. */
@Composable
private fun ReadBadge(origin: ThumbOrigin, entry: FileEntry, modifier: Modifier) {
    val ctx = LocalContext.current
    val store = remember { ProgressStore.get(ctx) }
    val version = store.version // 뷰어를 닫으면 바뀌어서 다시 그린다
    val p = remember(version, entry.path, entry.size) { store.get(store.keyFor(origin, entry)) }
    if (!p.started) return
    Box(modifier.fillMaxWidth()) {
        if (p.finished) {
            Text(
                "✓ 다 읽음",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color(0xCC2E7D32), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        } else {
            Text(
                "${p.percent}%",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 10.dp)
                    .background(Badge, RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color(0x66000000))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(p.fraction)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

/** 탭 아래에 들어가는 얇은 제목줄 (뒤로 · 제목/경로 · 버튼들). */
@Composable
fun BrowserHeader(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "뒤로",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

@Composable
fun StarButton(on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Text(
            if (on) "★" else "☆",
            style = MaterialTheme.typography.titleLarge,
            color = if (on) StarColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

val StarColor = Color(0xFFFFC94D)

/** 책장에서 한 줄에 보여줄 표지 개수 (설정에서 바꿈). */
val LocalGridColumns = compositionLocalOf { 3 }
private val Badge = Color(0x99000000)

@Composable
fun ErrorView(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("⚠️", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("다시 시도") }
    }
}
