package com.kakario.comicviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakario.comicviewer.thumb.ThumbnailLoader

private fun ThumbnailLoader.JobInfo.countText(): String =
    "$done/${total ?: "?"}"

/** 탭 아래 한 줄: 갱신 중일 때만 보이고, 누르면 상세 창이 열리고 닫힌다. */
@Composable
fun ThumbStatusBar(
    status: ThumbnailLoader.Status?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    AnimatedVisibility(
        visible = status != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val st = status ?: return@AnimatedVisibility
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "'${st.current.label}' ${st.phase}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                st.current.countText() + if (st.waiting.isNotEmpty()) " · 대기 ${st.waiting.size}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            AppIcon(if (expanded) AppIcons.Up else AppIcons.Down, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 상세 창: 책장 위로 반투명하게 내려온다.
 * 지금 갱신 중인 폴더(13/1000)와 대기 중인 폴더 목록(폴더명 0/100 대기 중).
 */
@Composable
fun ThumbStatusPanel(
    status: ThumbnailLoader.Status?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRemoveWaiting: (Long) -> Unit,
    onSkipCurrent: () -> Unit,
    onStopAll: () -> Unit,
) {
    val visible = expanded && status != null
    BackHandler(enabled = visible, onBack = onDismiss)
    // 뒤쪽 어둡게 + 바깥을 누르면 닫힘
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x55000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        val st = status ?: return@AnimatedVisibility
        val panelBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(panelBg)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // ── 지금 갱신 중
            Text(
                "지금 갱신 중",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    st.current.label + if (st.current.auto) "  (지금 보는 폴더)" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    st.current.countText(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            val total = st.current.total
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { st.current.done.toFloat() / total },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            } else {
                LinearProgressIndicator(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }

            // ── 대기 중
            Spacer(Modifier.height(14.dp))
            Text(
                if (st.waiting.isEmpty()) "대기 중인 폴더 없음" else "대기 중 ${st.waiting.size}개",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (st.waiting.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(st.waiting, key = { it.id }) { w ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                w.label + if (w.auto) "  (지금 보는 폴더)" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${w.countText()} 대기 중",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { onRemoveWaiting(w.id) }) { Text("빼기") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSkipCurrent) { Text("이 폴더 건너뛰기") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onStopAll) { Text("모두 중지", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        }
    }
}
