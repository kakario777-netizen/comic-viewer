package com.kakario.comicviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.UserFacingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface LoadState {
    data object Loading : LoadState
    data class Loaded(val entries: List<FileEntry>) : LoadState
    data class Failed(val message: String) : LoadState
}

/**
 * FTP와 내 폰이 함께 쓰는 폴더 탐색 화면.
 * 지금 보고 있는 폴더(path)는 바깥에서 관리해서, 탭을 옮겨 다녀도 위치가 유지된다.
 */
@Composable
fun FolderBrowser(
    title: String,
    path: String,
    load: suspend (String) -> List<FileEntry>,
    onUp: () -> Unit,
    onOpenDir: (String) -> Unit,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (path: String) -> Unit,
) {
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val currentLoad by rememberUpdatedState(load)
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

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BrowserHeader(
                title = title,
                subtitle = path,
                onBack = onUp,
                actions = {
                    StarButton(
                        on = isFavorite(path),
                        onClick = {
                            val wasOn = isFavorite(path)
                            onToggleFavorite(path)
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar(
                                    if (wasOn) "즐겨찾기에서 뺐어요" else "즐겨찾기에 추가했어요 — 첫 탭에서 바로 열 수 있어요"
                                )
                            }
                        },
                    )
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
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(visible, key = { it.path }) { entry ->
                                    EntryRow(
                                        entry = entry,
                                        favorite = entry.isDirectory && isFavorite(entry.path),
                                        onToggleFavorite = { onToggleFavorite(entry.path) },
                                        onClick = {
                                            if (entry.isDirectory) {
                                                onOpenDir(entry.path)
                                            } else {
                                                scope.launch {
                                                    snackbar.currentSnackbarData?.dismiss()
                                                    snackbar.showSnackbar("ZIP 보기는 다음 업데이트에서 열려요")
                                                }
                                            }
                                        },
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
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
            color = if (on) Star else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val Star = androidx.compose.ui.graphics.Color(0xFFFFC94D)

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

@Composable
private fun EntryRow(
    entry: FileEntry,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (entry.isDirectory) "📁" else "📦", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDirectory) {
                Text(
                    formatSize(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (entry.isDirectory) {
            StarButton(on = favorite, onClick = onToggleFavorite)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
