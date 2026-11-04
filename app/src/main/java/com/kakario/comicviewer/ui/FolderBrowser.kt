package com.kakario.comicviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.produceState
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.kakario.comicviewer.dup.DupScanner
import com.kakario.comicviewer.dup.FolderDiff
import com.kakario.comicviewer.dup.DupGroup
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
import com.kakario.comicviewer.data.SettingsStore
import com.kakario.comicviewer.data.SortKey
import com.kakario.comicviewer.model.NaturalOrder
import com.kakario.comicviewer.model.sortedForShelf
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.UserFacingException
import com.kakario.comicviewer.model.folderName
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

    // ── 지금 보는 폴더만 자동으로 없는 표지를 채운다 (나가면 멈춤)
    val thumbs = LocalThumbnails.current
    val loaded = (state as? LoadState.Loaded)?.entries
    DisposableEffect(thumbs, origin, path, loaded != null) {
        if (thumbs != null && loaded != null && loaded.any { it.isComicArchive }) {
            thumbs.watchFolder(origin, path, folderName(path), loaded.count { it.isComicArchive })
        }
        onDispose { thumbs?.unwatchFolder(origin, path) }
    }

    // ── 정렬 (설정에 저장 — FTP·내 폰 모두 같은 방식)
    val settings = remember { SettingsStore(context) }
    var sortKey by remember { mutableStateOf(settings.sortKey) }
    var sortAsc by remember { mutableStateOf(settings.sortAscending) }
    var foldersFirst by remember { mutableStateOf(settings.foldersFirst) }
    var sortOpen by remember { mutableStateOf(false) }

    // ── 중복 검사
    val dup = remember { DupScanner.get(context) }
    val dupState by dup.state.collectAsState()
    val dupKey = dup.keyOf(origin, path)
    var watched by remember(dupKey) { mutableStateOf(dup.isWatched(origin, path)) }
    var dupMenu by remember { mutableStateOf(false) }
    var updateDiff by remember { mutableStateOf<FolderDiff?>(null) }
    var progressHidden by remember { mutableStateOf(false) }
    // 처리 안 한 중복 묶음 (null = 아직 검사한 적 없음)
    var pending by remember(dupKey) { mutableStateOf<List<DupGroup>?>(null) }
    var pendingLoading by remember { mutableStateOf(false) }
    var pendingRefresh by remember { mutableIntStateOf(0) }
    var savedNote by remember { mutableStateOf<String?>(null) }
    var comparing by remember { mutableStateOf<DupGroup?>(null) }
    val presentZips = loaded?.filter { it.isComicArchive }?.associateBy { it.name }

    // 중복 검사 폴더에 들어오면 기록과 비교 → 새 자료가 있으면 물어본다
    LaunchedEffect(dupKey, loaded, watched) {
        updateDiff = null
        if (!watched || loaded == null) return@LaunchedEffect
        if (dup.isMuted(origin, path, loaded)) return@LaunchedEffect // '보지 않음' — 다음 갱신 전까지
        val running = dupState
        if (running is DupScanner.State.Running && running.key == dupKey) return@LaunchedEffect
        val d = try {
            dup.checkChanges(origin, path, loaded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (d != null && d.hasNew) updateDiff = d
    }
    // 처리 안 한 중복 수 (버튼 옆 숫자) — 검사 폴더이거나 창을 열었을 때만 읽음
    LaunchedEffect(dupKey, loaded, watched, dupMenu, pendingRefresh) {
        if (loaded == null || (!watched && !dupMenu)) return@LaunchedEffect
        pendingLoading = pending == null
        pending = try {
            dup.pending(origin, path, presentZips?.keys)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        pendingLoading = false
    }
    // 검사가 끝나면 결과(처리할 목록) 창을 띄운다
    LaunchedEffect(dupState) {
        val ds = dupState
        if (ds is DupScanner.State.Done && ds.key == dupKey) {
            savedNote = if (ds.savedInFolder) "ZIP ${ds.checked}개 확인 · 기록은 폴더 안 숨김 파일에 저장"
            else "ZIP ${ds.checked}개 확인 · 폴더에 쓸 권한이 없어 기록은 폰에 저장"
            dup.dismissResult()
            pendingRefresh++
            dupMenu = true
        }
    }
    // 중복 목록 스크롤 위치 — 비교 화면에 다녀와도 보던 자리 그대로
    val pendingListState = rememberLazyListState()

    /** 지운 파일을 목록·중복 묶음에서 바로 뺀다 (폴더를 다시 읽지 않아서 스크롤이 안 튐). */
    fun removeLocally(name: String) {
        (state as? LoadState.Loaded)?.let { st -> state = LoadState.Loaded(st.entries.filter { it.name != name }) }
        pending = pending
            ?.map { g -> g.copy(items = g.items.filter { it.name != name }) }
            ?.filter { it.items.size >= 2 }
    }

    fun startDupScan() {
        val list = loaded ?: return
        progressHidden = false
        dup.scan(origin, path, folderName(path), list)
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
                // 파일·폴더 개수를 앞에, 경로는 뒤에 (길면 뒤가 잘림)
                subtitle = loaded?.let { list ->
                    val files = list.count { it.isComicArchive }
                    val dirs = list.count { it.isDirectory }
                    buildString {
                        append("파일 ${files}개")
                        if (dirs > 0) append(" · 폴더 ${dirs}개")
                        append("  ·  ")
                        append(path)
                    }
                } ?: path,
                onBack = onUp,
                actions = {
                    TextButton(onClick = { dupMenu = true }) {
                        Text(
                            when {
                                (pending?.size ?: 0) > 0 -> "중복 ${pending!!.size}"
                                watched -> "중복✓"
                                else -> "중복"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (watched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { sortOpen = true }) {
                        Text("정렬", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
                            .sortedForShelf(sortKey, sortAsc, foldersFirst)
                        // '다음 권'은 정렬과 상관없이 이름순(1권 → 2권)으로
                        val bookOrder = s.entries.filter { it.isComicArchive }.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
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
                                            else onOpenArchive(entry, bookOrder)
                                        },
                                        onLongClick = { menuFor = entry },
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

    // ── 정렬 창
    if (sortOpen) {
        SortDialog(
            key = sortKey,
            ascending = sortAsc,
            foldersFirst = foldersFirst,
            onChange = { k, asc, ff ->
                sortKey = k; sortAsc = asc; foldersFirst = ff
                settings.sortKey = k; settings.sortAscending = asc; settings.foldersFirst = ff
            },
            onClose = { sortOpen = false },
        )
    }

    // ── 중복 검사 창들
    if (dupMenu && comparing == null) {
        DupPendingDialog(
            folderLabel = folderName(path),
            pending = pending,
            loading = pendingLoading,
            savedNote = savedNote,
            watched = watched,
            onWatchChange = {
                watched = it
                dup.setWatched(origin, path, it)
            },
            onScanNow = {
                dupMenu = false
                startDupScan()
            },
            onCompare = { comparing = it },
            onIgnore = { g ->
                pending = pending?.filter { it.signature != g.signature } // 목록에서 바로 빼기
                scope.launch {
                    dup.ignore(origin, path, g)
                    toast("무시했어요 — 이 묶음에 새 파일이 생기면 다시 보여요")
                }
            },
            onClose = {
                dupMenu = false
                savedNote = null
            },
            listState = pendingListState,
        )
    }
    // 비교 화면은 앱 화면 맨 위(탭까지 덮는 자리)에 그린다
    val overlay = LocalOverlayHost.current
    val compareContent: (@Composable () -> Unit)? = comparing?.let { g ->
        {
            DupCompareScreen(
                origin = origin,
                group = g,
                files = presentZips.orEmpty(),
                onDelete = { b ->
                    val target = presentZips?.get(b.name)
                    if (target != null) {
                        currentDelete(target)
                        progressStore.clear(progressStore.keyFor(origin, target))
                    }
                    dup.forget(origin, path, b.name)
                    removeLocally(b.name)
                },
                onIgnore = {
                    comparing = null
                    pending = pending?.filter { it.signature != g.signature }
                    scope.launch {
                        dup.ignore(origin, path, g)
                        toast("무시했어요")
                    }
                },
                onClose = { comparing = null },
            )
        }
    }
    val latestCompare by rememberUpdatedState(compareContent)
    if (overlay == null) {
        compareContent?.invoke()
    } else {
        DisposableEffect(overlay, comparing != null) {
            overlay.content = if (comparing != null) ({ latestCompare?.invoke() }) else null
            onDispose { overlay.content = null }
        }
    }
    updateDiff?.let { d ->
        DupUpdateDialog(
            diff = d,
            onCheck = {
                updateDiff = null
                startDupScan()
            },
            onLater = { updateDiff = null }, // 다음에 들어오면 다시 물어봄
            onMute = {
                updateDiff = null
                loaded?.let { dup.mute(origin, path, it) }
                toast("다음 자료 갱신 전까지 묻지 않을게요")
            },
        )
    }
    when (val ds = dupState) {
        is DupScanner.State.Running -> if (ds.key == dupKey && !progressHidden) {
            DupProgressDialog(
                state = ds,
                onHide = {
                    progressHidden = true
                    toast("중복 검사는 계속 진행돼요 — 끝나면 결과가 떠요")
                },
                onCancel = { dup.cancel() },
            )
        }
        is DupScanner.State.Failed -> if (ds.key == dupKey) {
            AlertDialog(
                onDismissRequest = { dup.dismissResult() },
                title = { Text("중복 검사") },
                text = { Text(ds.message) },
                confirmButton = { TextButton(onClick = { dup.dismissResult() }) { Text("확인") } },
            )
        }
        else -> Unit
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
                    if (target.isComicArchive) {
                        // 파일 정보: 용량과 이미지 수 (이미지 수는 ZIP 목차만 읽어서 알아냄)
                        val count by produceState<String>("세는 중…", target) {
                            value = try {
                                thumbs?.pageCount(origin, target)?.let { "${it}장" } ?: "알 수 없음"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                "알 수 없음"
                            }
                        }
                        InfoLine("용량", formatBytes(target.size))
                        InfoLine("이미지 수", count)
                        val p = remember(target, progressStore.version) {
                            progressStore.get(progressStore.keyFor(origin, target))
                        }
                        if (p.started) InfoLine("읽은 위치", "${p.page + 1} / ${p.total}쪽 (${p.percent}%)")
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        MenuItem("🖼️  표지 다시 만들기") {
                            menuFor = null
                            thumbs?.invalidate(origin, listOf(target))
                            toast("표지를 다시 만들어요")
                        }
                        MenuItem("📖  읽은 진행률 초기화", enabled = started) {
                            menuFor = null
                            confirm = Confirm(target, ConfirmAction.RESET, 1)
                        }
                        MenuItem("🗑️  파일 삭제", color = MaterialTheme.colorScheme.error) {
                            menuFor = null
                            confirm = Confirm(target, ConfirmAction.DELETE, 1)
                        }
                    } else {
                        MenuItem("🖼️  썸네일 갱신하기 (없는 표지만 만들기)") {
                            menuFor = null
                            val added = thumbs?.requestFolder(origin, target.path, target.name) ?: false
                            toast(
                                if (added) "'${target.name}' 썸네일 갱신을 줄에 올렸어요 — 위쪽에 진행 표시가 떠요"
                                else "'${target.name}'은 이미 갱신 중이거나 대기 중이에요"
                            )
                        }
                        val fav = isFavorite(target.path)
                        MenuItem(if (fav) "☆  즐겨찾기에서 빼기" else "⭐  즐겨찾기에 추가") {
                            menuFor = null
                            toggle(target.path)
                        }
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
                                removeLocally(c.entry.name)
                                scope.launch { runCatching { dup.forget(origin, path, c.entry.name) } }
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

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
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
            val tileModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
            if (entry.isDirectory) {
                // 하위 폴더는 썸네일 없이 폴더 아이콘만
                Box(
                    tileModifier.background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("📁", style = MaterialTheme.typography.displaySmall)
                }
            } else {
                CoverImage(origin = origin, entry = entry, modifier = tileModifier)
            }
            if (entry.isComicArchive) {
                ReadBadge(origin, entry, Modifier.align(Alignment.BottomCenter))
                NewDot(origin, entry, Modifier.align(Alignment.TopEnd))
            }
            if (entry.isDirectory) {
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

/** 새 만화 표시: 올라온 지 한 달이 안 됐고 아직 안 본 책이면 표지 오른쪽 위에 빨간 점. */
@Composable
private fun NewDot(origin: ThumbOrigin, entry: FileEntry, modifier: Modifier) {
    if (entry.modified <= 0L) return
    val age = System.currentTimeMillis() - entry.modified
    if (age > 30L * 24 * 60 * 60 * 1000) return
    val ctx = LocalContext.current
    val store = remember { ProgressStore.get(ctx) }
    val version = store.version
    val seen = remember(version, entry.path, entry.size) { store.get(store.keyFor(origin, entry)).started }
    if (seen) return
    Box(
        modifier
            .padding(6.dp)
            .size(12.dp)
            .clip(CircleShape)
            .background(Color.White)
            .padding(2.dp)
            .clip(CircleShape)
            .background(Color(0xFFE53935))
    )
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

/** 정렬 창: 폴더 위치 · 기준 · 순서 — 누르는 즉시 적용. */
@Composable
private fun SortDialog(
    key: SortKey,
    ascending: Boolean,
    foldersFirst: Boolean,
    onChange: (SortKey, Boolean, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    @Composable
    fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
    @Composable
    fun Section(title: String) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("정렬") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Section("폴더 위치")
                Choice("폴더를 위로", foldersFirst) { onChange(key, ascending, true) }
                Choice("폴더를 아래로", !foldersFirst) { onChange(key, ascending, false) }
                Section("정렬 기준")
                SortKey.entries.forEach { k ->
                    Choice(k.label, k == key) { onChange(k, ascending, foldersFirst) }
                }
                Section("순서")
                Choice("${key.ascLabel} ↑", ascending) { onChange(key, true, foldersFirst) }
                Choice("${key.descLabel} ↓", !ascending) { onChange(key, false, foldersFirst) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )
}
