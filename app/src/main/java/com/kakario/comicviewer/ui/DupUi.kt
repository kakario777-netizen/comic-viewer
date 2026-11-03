package com.kakario.comicviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakario.comicviewer.dup.BookPrint
import com.kakario.comicviewer.dup.DupGroup
import com.kakario.comicviewer.dup.DupScanner
import com.kakario.comicviewer.dup.FolderDiff

/** 폴더 제목줄 '중복' 버튼을 누르면 나오는 메뉴. */
@Composable
fun DupMenuDialog(
    folderLabel: String,
    watched: Boolean,
    onWatchChange: (Boolean) -> Unit,
    onScanNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("중복 검사") },
        text = {
            Column {
                Text(
                    "'$folderLabel'",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onWatchChange(!watched) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("중복 검사 폴더로 지정", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "들어올 때 새로 들어온 자료가 있으면 알려드려요",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = watched, onCheckedChange = null)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "검사 기록은 이 폴더 안에 숨김 파일(.comicviewer_dup.json)로 남겨서, 다음엔 바뀐 파일만 다시 확인해요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onScanNow) { Text("지금 검사") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** 중복 검사 폴더에 들어왔는데 자료가 바뀌었을 때. */
@Composable
fun DupUpdateDialog(diff: FolderDiff, onCheck: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("신규 자료가 갱신되었습니다") },
        text = {
            Column {
                val parts = buildList {
                    if (diff.added > 0) add("새 파일 ${diff.added}개")
                    if (diff.changed > 0) add("바뀐 파일 ${diff.changed}개")
                    if (diff.removed > 0) add("없어진 파일 ${diff.removed}개")
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text("중복 체크하시겠습니까?\n(바뀐 파일만 확인해서 빨리 끝나요)")
            }
        },
        confirmButton = { TextButton(onClick = onCheck) { Text("체크하기") } },
        dismissButton = { TextButton(onClick = onLater) { Text("나중에") } },
    )
}

/** 검사 진행 창. */
@Composable
fun DupProgressDialog(state: DupScanner.State.Running, onHide: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onHide,
        title = { Text("중복 검사 중") },
        text = {
            Column {
                Text(
                    "'${state.label}'",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.padding(start = 10.dp))
                    Text("${state.done}/${state.total}", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "파일 전체를 받지 않고 목차만 읽어서 확인해요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onHide) { Text("숨기기") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("중지") } },
    )
}

/** 검사 결과: 중복 묶음 목록, 각 파일 열기·삭제(두 번 확인). */
@Composable
fun DupResultDialog(
    state: DupScanner.State.Done,
    onOpen: (BookPrint) -> Unit,
    onDelete: (BookPrint) -> Unit,
    onClose: () -> Unit,
) {
    // 삭제 확인 단계: 대상, 단계(1 또는 2)
    var deleting by remember { mutableStateOf<Pair<BookPrint, Int>?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(if (state.groups.isEmpty()) "중복 없음" else "중복 ${state.groups.size}묶음 찾았어요")
        },
        text = {
            Column {
                Text(
                    "'${state.label}' · ZIP ${state.checked}개 확인" +
                        if (state.savedInFolder) "" else "\n(폴더에 쓸 권한이 없어 기록은 폰에 저장했어요)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.groups.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("겹치는 책이 없어요")
                } else {
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        state.groups.forEachIndexed { gi, g ->
                            item(key = "h$gi") {
                                if (gi > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                Text(
                                    g.reason.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (g.reason == DupGroup.Reason.SAME_CONTENT) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                            items(g.items, key = { "g$gi:${it.name}" }) { b ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onOpen(b) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            b.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${sizeText(b.size)} · ${if (b.pages > 0) "${b.pages}장" else "이미지 수 모름"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { deleting = b to 1 }) {
                                        Text("삭제", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "이름을 누르면 바로 열어서 비교할 수 있어요",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )

    deleting?.let { (b, step) ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (step == 1) "파일을 삭제할까요?" else "정말 삭제할까요?") },
            text = {
                Text(if (step == 1) "'${b.name}'\n${sizeText(b.size)}" else "삭제한 파일은 되돌릴 수 없어요.")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (step == 1) {
                        deleting = b to 2
                    } else {
                        deleting = null
                        onDelete(b)
                    }
                }) {
                    Text(
                        if (step == 1) "다음" else "네, 삭제할게요",
                        color = if (step == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
    else -> String.format("%.0f KB", bytes / 1024.0)
}
