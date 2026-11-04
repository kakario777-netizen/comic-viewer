package com.kakario.comicviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

/** 중복 검사 폴더에 들어왔는데 자료가 바뀌었을 때: 체크하기 / 나중에 / 보지 않음. */
@Composable
fun DupUpdateDialog(diff: FolderDiff, onCheck: () -> Unit, onLater: () -> Unit, onMute: () -> Unit) {
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
                Text("중복 체크하시겠습니까?")
                Spacer(Modifier.height(10.dp))
                Text(
                    "나중에: 다음에 이 폴더에 들어오면 다시 물어봐요\n보지 않음: 다음 자료 갱신 전까지 묻지 않아요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onMute) { Text("보지 않음") }
                TextButton(onClick = onLater) { Text("나중에") }
                TextButton(onClick = onCheck) { Text("체크하기") }
            }
        },
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
                    Spacer(Modifier.width(10.dp))
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

/**
 * '중복' 버튼 창: 검사는 했지만 아직 처리(삭제·무시) 안 한 중복 묶음 목록.
 * pending == null 이면 아직 한 번도 검사 안 한 폴더.
 */
@Composable
fun DupPendingDialog(
    folderLabel: String,
    pending: List<DupGroup>?,
    loading: Boolean,
    savedNote: String?,
    watched: Boolean,
    onWatchChange: (Boolean) -> Unit,
    onScanNow: () -> Unit,
    onCompare: (DupGroup) -> Unit,
    onIgnore: (DupGroup) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                when {
                    loading -> "중복 확인 중…"
                    pending == null -> "중복 검사"
                    pending.isEmpty() -> "처리할 중복 없음"
                    else -> "처리할 중복 ${pending.size}묶음"
                }
            )
        },
        text = {
            Column {
                Text(
                    "'$folderLabel'",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onWatchChange(!watched) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("중복 검사 폴더로 지정", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "들어올 때 새 자료가 있으면 알려드려요",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = watched, onCheckedChange = null)
                }
                savedNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                when {
                    loading -> Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                    pending == null -> Text("아직 이 폴더를 검사한 적이 없어요.\n'지금 검사'를 눌러 시작하세요.")
                    pending.isEmpty() -> Text("남은 중복이 없어요.\n(삭제하거나 무시한 묶음은 여기서 빠져요)")
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        pending.forEachIndexed { i, g ->
                            item(key = g.signature) {
                                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                PendingGroupRow(g, onCompare = { onCompare(g) }, onIgnore = { onIgnore(g) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onScanNow) { Text("지금 검사") }
                TextButton(onClick = onClose) { Text("닫기") }
            }
        },
    )
}

@Composable
private fun PendingGroupRow(g: DupGroup, onCompare: () -> Unit, onIgnore: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            g.reason.label + " · ${g.items.size}개",
            style = MaterialTheme.typography.labelLarge,
            color = if (g.reason == DupGroup.Reason.SAME_CONTENT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        g.items.forEach { b ->
            Text(
                "- ${b.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "   ${sizeText(b.size)} · ${if (b.pages > 0) "${b.pages}장" else "이미지 수 모름"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onIgnore) { Text("무시") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = onCompare) { Text("비교·삭제") }
        }
    }
}

fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
    else -> String.format("%.0f KB", bytes / 1024.0)
}

/** 삭제 두 번 확인 (공용). */
@Composable
fun DeleteTwiceDialog(name: String, size: Long, step: Int, onNext: () -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (step == 1) "파일을 삭제할까요?" else "정말 삭제할까요?") },
        text = { Text(if (step == 1) "'$name'\n${sizeText(size)}" else "삭제한 파일은 되돌릴 수 없어요.") },
        confirmButton = {
            TextButton(onClick = { if (step == 1) onNext() else onConfirm() }) {
                Text(
                    if (step == 1) "다음" else "네, 삭제할게요",
                    color = if (step == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("취소") } },
    )
}
