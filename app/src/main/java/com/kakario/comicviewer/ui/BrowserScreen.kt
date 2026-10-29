package com.kakario.comicviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakario.comicviewer.data.FtpServer
import com.kakario.comicviewer.ftp.FtpBrowser
import com.kakario.comicviewer.ftp.FtpUserException
import com.kakario.comicviewer.ftp.RemoteEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

private sealed interface LoadState {
    data object Loading : LoadState
    data class Loaded(val entries: List<RemoteEntry>) : LoadState
    data class Failed(val message: String) : LoadState
}

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
@Composable
fun BrowserScreen(server: FtpServer, onExit: () -> Unit) {
    val browser = remember(server.id) { FtpBrowser(server) }
    DisposableEffect(browser) {
        // 화면을 나가면 연결을 끊는다 (화면이 사라져도 끝까지 실행되도록 전역 스코프 사용).
        onDispose { GlobalScope.launch { browser.close() } }
    }

    var rootPath by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path, reloadKey) {
        state = LoadState.Loading
        state = try {
            val target = path ?: browser.connect().also {
                rootPath = it
                path = it
            }
            LoadState.Loaded(browser.list(target))
        } catch (e: CancellationException) {
            throw e
        } catch (e: FtpUserException) {
            LoadState.Failed(e.message ?: "알 수 없는 오류")
        } catch (e: Exception) {
            LoadState.Failed("예상치 못한 오류: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    val current = path
    val atRoot = current == null || current == rootPath || current == "/"
    val goUp: () -> Unit = {
        if (atRoot || current == null) {
            onExit()
        } else {
            path = FtpBrowser.parentPath(current)
        }
    }
    BackHandler { goUp() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = goUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                title = {
                    Column {
                        Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            current ?: "접속 중…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { reloadKey++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "새로고침")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (val s = state) {
                LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is LoadState.Failed -> Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("⚠️", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        s.message,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { reloadKey++ }) { Text("다시 시도") }
                }

                is LoadState.Loaded -> {
                    val visible = s.entries.filter { it.isDirectory || it.isComicArchive }
                    if (visible.isEmpty()) {
                        Text(
                            "이 폴더에는 하위 폴더나 ZIP 파일이 없어요",
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(visible, key = { it.path }) { entry ->
                                EntryRow(entry) {
                                    if (entry.isDirectory) {
                                        path = entry.path
                                    } else {
                                        scope.launch {
                                            snackbar.currentSnackbarData?.dismiss()
                                            snackbar.showSnackbar("ZIP 보기는 다음 업데이트에서 열려요")
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: RemoteEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(if (entry.isDirectory) "📁" else "📦", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
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
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
