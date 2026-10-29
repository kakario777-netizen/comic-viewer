package com.kakario.comicviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kakario.comicviewer.data.FtpServer
import com.kakario.comicviewer.ftp.FtpBrowser
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.UserFacingException
import com.kakario.comicviewer.model.parentPath
import com.kakario.comicviewer.thumb.ThumbOrigin
import kotlinx.coroutines.CancellationException

/** FTP 탭에서 지금 열려 있는 서버와 위치. */
data class FtpLocation(
    val server: FtpServer,
    /** null이면 아직 접속 전 — 접속 후 서버의 시작 폴더로 간다. */
    val path: String?,
    /** 서버의 시작 폴더. 여기서 뒤로 가면 서버 목록으로 나간다. */
    val root: String?,
)

@Composable
fun FtpTab(
    servers: List<FtpServer>,
    location: FtpLocation?,
    browser: FtpBrowser?,
    onLocationChange: (FtpLocation?) -> Unit,
    onOpenArchive: (FileEntry) -> Unit,
    onSaveServer: (FtpServer) -> Unit,
    onDeleteServer: (FtpServer) -> Unit,
    isFavorite: (serverId: Long, path: String) -> Boolean,
    onToggleFavorite: (serverId: Long, path: String) -> Unit,
) {
    if (location == null || browser == null) {
        ServerListScreen(
            servers = servers,
            onOpen = { onLocationChange(FtpLocation(it, path = null, root = null)) },
            onSave = onSaveServer,
            onDelete = onDeleteServer,
        )
        return
    }

    val path = location.path
    if (path == null) {
        ConnectingView(location, browser, onLocationChange)
        return
    }

    val serverId = location.server.id
    FolderBrowser(
        title = location.server.name,
        path = path,
        origin = ThumbOrigin(location.server),
        load = { browser.list(it) },
        onUp = {
            val atTop = path == location.root || path == "/"
            if (atTop) onLocationChange(null)
            else onLocationChange(location.copy(path = parentPath(path)))
        },
        onOpenDir = { onLocationChange(location.copy(path = it)) },
        onOpenArchive = onOpenArchive,
        isFavorite = { isFavorite(serverId, it) },
        onToggleFavorite = { onToggleFavorite(serverId, it) },
    )
}

/** 처음 접속해서 서버의 시작 폴더를 알아내는 동안 보여주는 화면. */
@Composable
private fun ConnectingView(
    location: FtpLocation,
    browser: FtpBrowser,
    onLocationChange: (FtpLocation?) -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(browser, retry) {
        error = null
        try {
            val home = browser.connect()
            onLocationChange(location.copy(path = home, root = home))
        } catch (e: CancellationException) {
            throw e
        } catch (e: UserFacingException) {
            error = e.message
        } catch (e: Exception) {
            error = "예상치 못한 오류: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    BackHandler { onLocationChange(null) }

    Column(Modifier.fillMaxSize()) {
        BrowserHeader(
            title = location.server.name,
            subtitle = if (error == null) "접속 중…" else "접속 실패",
            onBack = { onLocationChange(null) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val msg = error
            if (msg == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                ErrorView(msg, onRetry = { retry++ }, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
