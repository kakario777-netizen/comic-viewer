package com.kakario.comicviewer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kakario.comicviewer.local.LocalStorage
import com.kakario.comicviewer.model.FileEntry
import com.kakario.comicviewer.model.parentPath
import com.kakario.comicviewer.thumb.ThumbOrigin

/**
 * 내 폰 탭. path가 null이면 저장공간(내장 메모리 / SD카드) 선택 화면.
 */
@Composable
fun LocalTab(
    path: String?,
    onPathChange: (String?) -> Unit,
    onOpenArchive: (entry: FileEntry, siblings: List<FileEntry>) -> Unit,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(LocalStorage.hasAccess(context)) }

    // 설정 화면에서 권한을 켜고 돌아오면 다시 확인
    LifecycleResumeEffect(Unit) {
        hasAccess = LocalStorage.hasAccess(context)
        onPauseOrDispose { }
    }

    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAccess = granted }

    if (!hasAccess) {
        PermissionView(onGrant = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                LocalStorage.openAllFilesSettings(context)
            } else {
                legacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        })
        return
    }

    if (path == null) {
        StorageRootList(onPick = { onPathChange(it) })
        return
    }

    FolderBrowser(
        title = "내 폰",
        path = path,
        origin = ThumbOrigin(null),
        load = { LocalStorage.list(it) },
        onUp = {
            if (LocalStorage.isRoot(context, path)) onPathChange(null)
            else onPathChange(parentPath(path))
        },
        onOpenDir = { onPathChange(it) },
        onOpenArchive = onOpenArchive,
        isFavorite = isFavorite,
        onToggleFavorite = onToggleFavorite,
    )
}

@Composable
private fun StorageRootList(onPick: (String) -> Unit) {
    val context = LocalContext.current
    val roots = remember { LocalStorage.roots(context) }
    Column(Modifier.fillMaxSize()) {
        BrowserHeader(title = "내 폰", subtitle = "저장공간을 선택하세요", onBack = null)
        LazyColumn {
            items(roots, key = { it.path }) { root ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(root.path) }
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (root.label == "내장 메모리") "📱" else "💾",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            root.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            root.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun PermissionView(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text("📱", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "폰 안의 만화를 보려면 권한이 필요해요",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "아래 버튼을 누르고 설정 화면에서\n'모든 파일 접근 허용'을 켠 뒤 돌아와 주세요",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onGrant) { Text("권한 허용하러 가기") }
        }
    }
}
