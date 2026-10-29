package com.kakario.comicviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kakario.comicviewer.data.Favorite
import com.kakario.comicviewer.data.FavoriteStore
import com.kakario.comicviewer.data.FtpServer
import com.kakario.comicviewer.data.ServerStore
import com.kakario.comicviewer.ftp.FtpBrowser
import com.kakario.comicviewer.model.folderName
import com.kakario.comicviewer.ui.FavoritesScreen
import com.kakario.comicviewer.ui.FtpLocation
import com.kakario.comicviewer.ui.FtpTab
import com.kakario.comicviewer.ui.LocalTab
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

val AppColors = darkColorScheme(
    primary = Color(0xFFE8505B),
    onPrimary = Color.White,
    background = Color(0xFF14161A),
    surface = Color(0xFF1E2127),
    surfaceVariant = Color(0xFF262A31),
    onBackground = Color(0xFFECECEC),
    onSurface = Color(0xFFECECEC),
    onSurfaceVariant = Color(0xFFB5B9C0),
    error = Color(0xFFFF8A80),
)

private val TABS = listOf("⭐ 즐겨찾기", "🗄️ FTP", "📱 내 폰")
private const val TAB_FAVORITES = 0
private const val TAB_FTP = 1
private const val TAB_LOCAL = 2

class MainActivity : ComponentActivity() {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serverStore = ServerStore(applicationContext)
        val favoriteStore = FavoriteStore(applicationContext)

        setContent {
            MaterialTheme(colorScheme = AppColors) {
                val servers = remember { mutableStateListOf<FtpServer>().apply { addAll(serverStore.load()) } }
                val favorites = remember { mutableStateListOf<Favorite>().apply { addAll(favoriteStore.load()) } }
                var tab by remember { mutableIntStateOf(TAB_FAVORITES) }

                // 각 탭의 현재 위치 — 탭을 옮겨 다녀도 그대로 남는다.
                var ftpLocation by remember { mutableStateOf<FtpLocation?>(null) }
                var localPath by remember { mutableStateOf<String?>(null) }

                // 열려 있는 서버의 FTP 연결 (서버가 바뀌거나 닫히면 끊는다)
                val openServer = ftpLocation?.server
                val ftpBrowser = remember(openServer) { openServer?.let { FtpBrowser(it) } }
                DisposableEffect(ftpBrowser) {
                    onDispose { ftpBrowser?.let { b -> GlobalScope.launch { b.close() } } }
                }

                fun isFavorite(serverId: Long?, path: String) =
                    favorites.any { it.matches(serverId, path) }

                fun toggleFavorite(serverId: Long?, path: String) {
                    val existing = favorites.firstOrNull { it.matches(serverId, path) }
                    if (existing != null) {
                        favorites.remove(existing)
                    } else {
                        favorites.add(Favorite(System.currentTimeMillis(), serverId, path, folderName(path)))
                    }
                    favoriteStore.save(favorites)
                }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TabRow(
                            selectedTabIndex = tab,
                            containerColor = MaterialTheme.colorScheme.background,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ) {
                            TABS.forEachIndexed { i, label ->
                                Tab(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    text = { Text(label) },
                                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        when (tab) {
                            TAB_FAVORITES -> FavoritesScreen(
                                favorites = favorites,
                                servers = servers,
                                onOpen = { fav ->
                                    if (fav.isLocal) {
                                        localPath = fav.path
                                        tab = TAB_LOCAL
                                    } else {
                                        val server = servers.firstOrNull { it.id == fav.serverId }
                                        if (server != null) {
                                            // 같은 서버가 이미 열려 있으면 연결을 재사용, root도 유지
                                            val keepRoot = ftpLocation?.takeIf { it.server.id == server.id }?.root
                                            ftpLocation = FtpLocation(server, fav.path, keepRoot)
                                            tab = TAB_FTP
                                        }
                                    }
                                },
                                onRemove = { fav ->
                                    favorites.removeAll { it.id == fav.id }
                                    favoriteStore.save(favorites)
                                },
                            )

                            TAB_FTP -> FtpTab(
                                servers = servers,
                                location = ftpLocation,
                                browser = ftpBrowser,
                                onLocationChange = { ftpLocation = it },
                                onSaveServer = { server ->
                                    val idx = servers.indexOfFirst { it.id == server.id }
                                    if (idx >= 0) servers[idx] = server else servers.add(server)
                                    serverStore.save(servers)
                                },
                                onDeleteServer = { server ->
                                    servers.removeAll { it.id == server.id }
                                    serverStore.save(servers)
                                    if (favorites.removeAll { it.serverId == server.id }) {
                                        favoriteStore.save(favorites)
                                    }
                                },
                                isFavorite = { id, path -> isFavorite(id, path) },
                                onToggleFavorite = { id, path -> toggleFavorite(id, path) },
                            )

                            TAB_LOCAL -> LocalTab(
                                path = localPath,
                                onPathChange = { localPath = it },
                                isFavorite = { isFavorite(null, it) },
                                onToggleFavorite = { toggleFavorite(null, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}
