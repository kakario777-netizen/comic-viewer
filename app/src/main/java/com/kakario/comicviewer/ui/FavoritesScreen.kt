package com.kakario.comicviewer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakario.comicviewer.data.Favorite
import com.kakario.comicviewer.data.FtpServer

@Composable
fun FavoritesScreen(
    favorites: List<Favorite>,
    servers: List<FtpServer>,
    onOpen: (Favorite) -> Unit,
    onRemove: (Favorite) -> Unit,
) {
    var removing by remember { mutableStateOf<Favorite?>(null) }

    if (favorites.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp),
            ) {
                Text("⭐", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    "즐겨찾기한 폴더가 없어요",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "FTP나 내 폰 탭에서 폴더 옆 ☆를 누르면\n여기에 모여서 바로 열 수 있어요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "누르면 바로 열리고, 길게 누르면 즐겨찾기에서 뺄 수 있어요",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(favorites, key = { it.id }) { fav ->
                val source = if (fav.isLocal) {
                    "📱 내 폰"
                } else {
                    "🗄️ " + (servers.firstOrNull { it.id == fav.serverId }?.name ?: "알 수 없는 서버")
                }
                FavoriteTile(
                    name = fav.name,
                    source = source,
                    onClick = { onOpen(fav) },
                    onLongClick = { removing = fav },
                )
            }
        }
    }

    removing?.let { fav ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("즐겨찾기 해제") },
            text = { Text("'${fav.name}' 폴더를 즐겨찾기에서 뺄까요?\n(실제 폴더나 파일은 지워지지 않아요)") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(fav)
                    removing = null
                }) { Text("빼기") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("취소") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteTile(
    name: String,
    source: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        // 나중에 이 자리에 첫 권 표지 썸네일이 들어간다.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text("📁", style = MaterialTheme.typography.displayMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            source,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
