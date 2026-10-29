package com.kakario.comicviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.kakario.comicviewer.data.FtpServer
import com.kakario.comicviewer.data.ServerStore
import com.kakario.comicviewer.ui.BrowserScreen
import com.kakario.comicviewer.ui.ServerListScreen

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ServerStore(applicationContext)

        setContent {
            MaterialTheme(colorScheme = AppColors) {
                val servers = remember { mutableStateListOf<FtpServer>().apply { addAll(store.load()) } }
                var openServer by remember { mutableStateOf<FtpServer?>(null) }

                val current = openServer
                if (current == null) {
                    ServerListScreen(
                        servers = servers,
                        onOpen = { openServer = it },
                        onSave = { server ->
                            val idx = servers.indexOfFirst { it.id == server.id }
                            if (idx >= 0) servers[idx] = server else servers.add(server)
                            store.save(servers)
                        },
                        onDelete = { server ->
                            servers.removeAll { it.id == server.id }
                            store.save(servers)
                        },
                    )
                } else {
                    BrowserScreen(
                        server = current,
                        onExit = { openServer = null },
                    )
                }
            }
        }
    }
}
