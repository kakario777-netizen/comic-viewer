package com.kakario.comicviewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakario.comicviewer.fix.ArchiveDoctor

/** 압축 오류 검사 결과: 2중 압축 풀기 / 오류 파일 옮기기를 여기서 묻는다. */
@Composable
fun ArchiveReportDialog(
    report: ArchiveDoctor.Report,
    onUnnest: () -> Unit,
    onMoveBroken: () -> Unit,
    onClose: () -> Unit,
) {
    val nested = report.nested
    val broken = report.broken
    val unknown = report.unknown
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (nested.isEmpty() && broken.isEmpty()) "압축 오류 없음" else "압축 오류 검사 결과") },
        text = {
            Column {
                Text(
                    "'${report.label}' · 정상 ${report.okCount} · 2중 압축 ${nested.size} · 오류 ${broken.size}" +
                        if (unknown.isNotEmpty()) " · 확인 못 함 ${unknown.size}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val problems = nested + broken + unknown
                if (problems.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("모든 파일이 정상이에요.")
                } else {
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(problems, key = { it.entry.path }) { d ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    d.entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    d.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (d.kind) {
                                        ArchiveDoctor.Kind.NESTED -> MaterialTheme.colorScheme.primary
                                        ArchiveDoctor.Kind.BROKEN -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (nested.isNotEmpty()) {
                        Text(
                            "2중 압축은 안쪽 압축을 꺼내 일반 만화 파일로 바꿔요. 원본은 폴더 안 숨김 폴더(${ArchiveDoctor.ORIGINAL_DIR})에 보관돼요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = onUnnest, modifier = Modifier.fillMaxWidth()) {
                            Text("2중 압축 ${nested.size}개 풀기")
                        }
                    }
                    if (broken.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "오류 파일은 같은 폴더의 '${ArchiveDoctor.ERROR_DIR}' 폴더로 옮겨요 (지우지 않아요).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = onMoveBroken, modifier = Modifier.fillMaxWidth()) {
                            Text("오류 파일 ${broken.size}개 옮기기", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )
}

/** 검사·풀기·옮기기 진행 창. */
@Composable
fun ArchiveWorkingDialog(state: ArchiveDoctor.State.Working) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(state.title) },
        text = {
            Column {
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
                    state.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {},
    )
}
