package com.kakario.comicviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kakario.comicviewer.data.PageLayout
import com.kakario.comicviewer.data.ThemeMode
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    rightToLeft: Boolean,
    onRightToLeftChange: (Boolean) -> Unit,
    pageLayout: PageLayout,
    onPageLayoutChange: (PageLayout) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val thumbs = LocalThumbnails.current
    var cacheBytes by remember { mutableLongStateOf(thumbs?.diskCacheBytes() ?: 0L) }
    // 썸네일 삭제 확인 단계: 0 = 닫힘, 1 = 첫 번째 확인, 2 = 마지막 확인
    var deleteStep by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"
    }

    Column(Modifier.fillMaxSize()) {
        BrowserHeader(title = "설정", subtitle = null, onBack = onClose)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ── 화면 테마
            SectionTitle("화면 테마")
            ThemeMode.entries.forEach { mode ->
                RadioRow(mode.label, selected = mode == themeMode) { onThemeChange(mode) }
            }

            Divider()

            // ── 책장 표지 크기
            SectionTitle("책장 표지 크기")
            var sliderValue by remember { mutableFloatStateOf(gridColumns.toFloat()) }
            val cols = sliderValue.roundToInt()
            Text(
                "한 줄에 표지 ${cols}개" + when (cols) {
                    2 -> " (가장 크게)"
                    6 -> " (가장 작게)"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Row(
                Modifier.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("크게", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onGridColumnsChange(sliderValue.roundToInt()) },
                    valueRange = 2f..6f,
                    steps = 3,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text("작게", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GridPreview(cols)

            Divider()

            // ── 보기 방식
            SectionTitle("만화 보기 방식")
            PageLayout.entries.forEach { layout ->
                RadioRow(layout.label, selected = layout == pageLayout) { onPageLayoutChange(layout) }
            }
            Text(
                "두 장 보기는 표지를 한 장으로 보여준 뒤 2장씩 펼쳐요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )

            Divider()

            // ── 넘기는 방향
            SectionTitle("만화 넘기는 방향")
            RadioRow("왼쪽 → 오른쪽 (한국·서양 만화)", selected = !rightToLeft) { onRightToLeftChange(false) }
            RadioRow("오른쪽 → 왼쪽 (일본 만화)", selected = rightToLeft) { onRightToLeftChange(true) }
            Text(
                "보는 중에 화면 가운데를 누르면 페이지 이동 막대와 한 장/두 장 전환, 넘김 방향 버튼이 나와요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )

            Divider()

            // ── 표지 썸네일
            SectionTitle("표지 썸네일")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "저장된 썸네일",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        formatMb(cacheBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { deleteStep = 1 },
                    enabled = cacheBytes > 0,
                ) { Text("지우기") }
            }
            Text(
                "표지가 바뀌었거나 이상하게 보일 때만 지우세요. 지우면 표지를 처음부터 다시 만들어요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )

            Divider()

            SectionTitle("앱 정보")
            Text(
                "코믹뷰어 버전 $version",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // 실수로 지우지 않도록 두 번 확인
    when (deleteStep) {
        1 -> AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            title = { Text("썸네일 지우기") },
            text = { Text("저장된 표지 썸네일 ${formatMb(cacheBytes)}를 지울까요?\n(만화 파일은 지워지지 않아요)") },
            confirmButton = { TextButton(onClick = { deleteStep = 2 }) { Text("다음") } },
            dismissButton = { TextButton(onClick = { deleteStep = 0 }) { Text("취소") } },
        )

        2 -> AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            title = { Text("정말 지울까요?") },
            text = {
                Text("지운 썸네일은 되돌릴 수 없어요.\nFTP 책장은 표지를 다시 받아오느라 처음 열 때 느려질 수 있어요.")
            },
            confirmButton = {
                TextButton(onClick = {
                    thumbs?.clear()
                    cacheBytes = thumbs?.diskCacheBytes() ?: 0L
                    deleteStep = 0
                }) { Text("네, 지울게요", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteStep = 0 }) { Text("취소") } },
        )
    }
}

/** 설정한 개수대로 한 줄 미리보기. */
@Composable
private fun GridPreview(columns: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(columns) {
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

private fun formatMb(bytes: Long): String =
    if (bytes < 1024 * 1024) "${bytes / 1024} KB" else String.format("%.1f MB", bytes / (1024.0 * 1024.0))
