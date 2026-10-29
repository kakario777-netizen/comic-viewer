package com.kakario.comicviewer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.kakario.comicviewer.thumb.ThumbWorkService
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.view.KeyEvent
import com.kakario.comicviewer.viewer.PageKeys
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import com.kakario.comicviewer.data.SettingsStore
import com.kakario.comicviewer.data.ThemeMode
import com.kakario.comicviewer.thumb.ThumbnailLoader
import com.kakario.comicviewer.ui.LocalThumbnails
import com.kakario.comicviewer.ui.LocalGridColumns
import com.kakario.comicviewer.ui.ViewerScreen
import com.kakario.comicviewer.ui.ThumbStatusBar
import com.kakario.comicviewer.ui.ThumbStatusPanel
import com.kakario.comicviewer.ui.ViewerPrefs
import com.kakario.comicviewer.thumb.ThumbOrigin
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.ui.SettingsScreen
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
import androidx.compose.runtime.LaunchedEffect
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

val DarkColors = darkColorScheme(
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

val LightColors = lightColorScheme(
    primary = Color(0xFFD93A46),
    onPrimary = Color.White,
    background = Color(0xFFF7F7F8),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9EBEE),
    onBackground = Color(0xFF1A1C1F),
    onSurface = Color(0xFF1A1C1F),
    onSurfaceVariant = Color(0xFF5F6368),
    error = Color(0xFFC62828),
)

/** 뷰어에 열려 있는 책과, 같은 폴더의 책 목록(다음 권 찾기용). */
data class OpenBook(
    val origin: ThumbOrigin,
    val entry: FileEntry,
    val siblings: List<FileEntry>,
) {
    val next: FileEntry?
        get() {
            val i = siblings.indexOfFirst { it.path == entry.path }
            return if (i >= 0) siblings.getOrNull(i + 1) else null
        }
}

private val TABS = listOf("⭐ 즐겨찾기", "🗄️ FTP", "📱 내 폰")
private const val TAB_FAVORITES = 0
private const val TAB_FTP = 1
private const val TAB_LOCAL = 2

class MainActivity : ComponentActivity() {
    private lateinit var thumbnails: ThumbnailLoader

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val serverStore = ServerStore(applicationContext)
        val favoriteStore = FavoriteStore(applicationContext)
        val settingsStore = SettingsStore(applicationContext)
        thumbnails = ThumbnailLoader.get(applicationContext)

        setContent {
            var themeMode by remember { mutableStateOf(settingsStore.themeMode) }
            var gridColumns by remember { mutableIntStateOf(settingsStore.gridColumns) }
            var rightToLeft by remember { mutableStateOf(settingsStore.rightToLeft) }
            var pageLayout by remember { mutableStateOf(settingsStore.pageLayout) }
            var showInfoBar by remember { mutableStateOf(settingsStore.showInfoBar) }
            var volumeKeys by remember { mutableStateOf(settingsStore.volumeKeys) }
            var ftpConnections by remember { mutableIntStateOf(settingsStore.ftpConnections) }
            var prefetchPages by remember { mutableIntStateOf(settingsStore.prefetchPages) }
            var slideAnimation by remember { mutableStateOf(settingsStore.slideAnimation) }
            val viewerPrefs = ViewerPrefs(pageLayout, rightToLeft, showInfoBar, volumeKeys, ftpConnections, prefetchPages, slideAnimation)
            fun saveViewerPrefs(p: ViewerPrefs) {
                pageLayout = p.pageLayout; settingsStore.pageLayout = p.pageLayout
                rightToLeft = p.rightToLeft; settingsStore.rightToLeft = p.rightToLeft
                showInfoBar = p.showInfoBar; settingsStore.showInfoBar = p.showInfoBar
                volumeKeys = p.volumeKeys; settingsStore.volumeKeys = p.volumeKeys
                ftpConnections = p.connections; settingsStore.ftpConnections = p.connections
                prefetchPages = p.prefetchPages; settingsStore.prefetchPages = p.prefetchPages
                slideAnimation = p.slideAnimation; settingsStore.slideAnimation = p.slideAnimation
            }
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // 상태바·내비게이션바 아이콘 색도 테마에 맞춘다
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }

            MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
              CompositionLocalProvider(
                LocalThumbnails provides thumbnails,
                LocalGridColumns provides gridColumns,
              ) {
                var settingsOpen by remember { mutableStateOf(false) }
                // 지금 보고 있는 만화책 (null이면 뷰어 닫힘)
                var openBook by remember { mutableStateOf<OpenBook?>(null) }
                // 만화를 보는 동안엔 폴더 표지 미리 만들기를 멈춰 보는 쪽에 속도를 양보
                LaunchedEffect(openBook != null) { thumbnails.paused.value = openBook != null }
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

                if (settingsOpen) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .windowInsetsPadding(WindowInsets.systemBars)
                    ) {
                        SettingsScreen(
                            themeMode = themeMode,
                            onThemeChange = {
                                themeMode = it
                                settingsStore.themeMode = it
                            },
                            gridColumns = gridColumns,
                            onGridColumnsChange = {
                                gridColumns = it
                                settingsStore.gridColumns = it
                            },
                            rightToLeft = rightToLeft,
                            onRightToLeftChange = {
                                rightToLeft = it
                                settingsStore.rightToLeft = it
                            },
                            pageLayout = pageLayout,
                            onPageLayoutChange = {
                                pageLayout = it
                                settingsStore.pageLayout = it
                            },
                            viewerPrefs = viewerPrefs,
                            onViewerPrefsChange = { saveViewerPrefs(it) },
                            onClose = { settingsOpen = false },
                        )
                    }
                } else {
                Box(Modifier.fillMaxSize()) {
                val thumbStatus by thumbnails.status.collectAsState()
                var statusPanelOpen by remember { mutableStateOf(false) }
                LaunchedEffect(thumbStatus == null) {
                    if (thumbStatus == null) {
                        statusPanelOpen = false
                    } else {
                        // 갱신이 시작되면 백그라운드 서비스를 켠다 → 화면이 꺼져도 계속 진행
                        askNotificationPermissionOnce()
                        ThumbWorkService.start(applicationContext)
                    }
                }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                      Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                        // 상태바(시간·아이콘) 바로 아래에 탭 + 오른쪽 끝 설정 버튼
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .windowInsetsPadding(WindowInsets.statusBars),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TabRow(
                                selectedTabIndex = tab,
                                modifier = Modifier.weight(1f),
                                containerColor = MaterialTheme.colorScheme.background,
                                contentColor = MaterialTheme.colorScheme.primary,
                                divider = {},
                            ) {
                                TABS.forEachIndexed { i, label ->
                                    Tab(
                                        selected = tab == i,
                                        onClick = { tab = i },
                                        text = { Text(label, maxLines = 1) },
                                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { settingsOpen = true }) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = "설정",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // 썸네일 갱신 중일 때만 보이는 한 줄 (끝나면 사라짐). 누르면 상세 창
                        ThumbStatusBar(
                            status = thumbStatus,
                            expanded = statusPanelOpen,
                            onToggle = { statusPanelOpen = !statusPanelOpen },
                        )
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
                                onOpenArchive = { entry, siblings ->
                                    ftpLocation?.let { openBook = OpenBook(ThumbOrigin(it.server), entry, siblings) }
                                },
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
                                onOpenArchive = { entry, siblings -> openBook = OpenBook(ThumbOrigin(null), entry, siblings) },
                                isFavorite = { isFavorite(null, it) },
                                onToggleFavorite = { toggleFavorite(null, it) },
                            )
                        }
                        // 썸네일 갱신 상세 창 — 책장 위로 반투명하게 내려온다
                        ThumbStatusPanel(
                            status = thumbStatus,
                            expanded = statusPanelOpen,
                            onDismiss = { statusPanelOpen = false },
                            onRemoveWaiting = { thumbnails.removeWaiting(it) },
                            onSkipCurrent = { thumbnails.skipCurrent() },
                            onStopAll = {
                                thumbnails.stopAll()
                                statusPanelOpen = false
                            },
                        )
                    }
                }
                // 만화 뷰어는 책장 위에 덮어서 띄운다 (닫으면 보던 책장 그대로)
                openBook?.let { book ->
                    ViewerScreen(
                        origin = book.origin,
                        entry = book.entry,
                        nextEntry = book.next,
                        prefs = viewerPrefs,
                        onPrefsChange = { saveViewerPrefs(it) },
                        onOpenNext = { next -> openBook = book.copy(entry = next) },
                        onClose = { openBook = null },
                    )
                }
                }
                } // else (설정 화면이 아닐 때)
              }
            }
        }
    }

    /** 안드로이드 13+: 진행 알림을 보여주려면 알림 권한이 필요 (처음 한 번만 물어봄). */
    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notification", false)) return
        prefs.edit().putBoolean("asked_notification", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }

    /** 뷰어가 열려 있으면 볼륨 버튼·페이지 터너 입력을 먼저 페이지 넘기기에 쓴다. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = PageKeys.handler
        if (handler != null && handler(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 썸네일 갱신은 백그라운드 서비스가 이어서 하므로 연결을 닫지 않는다
    }
}
