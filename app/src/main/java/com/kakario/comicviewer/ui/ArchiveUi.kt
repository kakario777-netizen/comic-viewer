package com.kakario.comicviewer.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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

/**
 * 압축 오류 검사 결과: 2중 압축 풀기 / 오류 파일 옮기기를 여기서 묻는다.
 * 2중 압축 파일을 누르면 안쪽 압축 목록을 보고 꺼낼 이름을 고칠 수 있다 (다음 → 다음 → 목록에서 전체 풀기).
 */
@Composable
fun ArchiveReportDialog(
    report: ArchiveDoctor.Report,
    onUnnest: (names: Map<String, List<String>>, only: Set<String>?) -> Unit,
    onMoveBroken: () -> Unit,
    onClose: () -> Unit,
) {
    val nested = report.nested
    val broken = report.broken
    val unknown = report.unknown
    // 바깥 파일 경로 → 꺼낼 이름들 (확장자 빼고)
    val names = remember(report.key) { mutableStateMapOf<String, List<String>>() }
    var editing by remember(report.key) { mutableStateOf<Int?>(null) }
    fun namesOf(d: ArchiveDoctor.Diagnosis) = names[d.entry.path] ?: ArchiveDoctor.defaultNames(d)

    val idx = editing?.takeIf { it in nested.indices }
    if (idx != null) {
        NestedEditDialog(
            d = nested[idx],
            index = idx,
            count = nested.size,
            names = namesOf(nested[idx]),
            onNames = { names[nested[idx].entry.path] = it },
            onPrev = { editing = idx - 1 },
            onNext = { editing = if (idx + 1 < nested.size) idx + 1 else null },
            onBack = { editing = null },
            onUnnestOne = { onUnnest(names.toMap(), setOf(nested[idx].entry.path)) },
        )
        return
    }

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
                    if (nested.isNotEmpty()) {
                        Text(
                            "2중 압축 파일을 누르면 안에 든 압축을 보고 이름을 고칠 수 있어요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(problems, key = { it.entry.path }) { d ->
                            val isNested = d.kind == ArchiveDoctor.Kind.NESTED
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (isNested) Modifier.clickable { editing = nested.indexOf(d) } else Modifier)
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    d.entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    d.reason + if (isNested) "  ›" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (d.kind) {
                                        ArchiveDoctor.Kind.NESTED -> MaterialTheme.colorScheme.primary
                                        ArchiveDoctor.Kind.BROKEN -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                if (isNested) {
                                    Text(
                                        "→ " + namesOf(d).joinToString(", ") { "$it.zip" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
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
                        if (nested.size > 1) {
                            OutlinedButton(onClick = { editing = 0 }, modifier = Modifier.fillMaxWidth()) {
                                Text("하나씩 확인·이름 고치기")
                            }
                        }
                        OutlinedButton(onClick = { onUnnest(names.toMap(), null) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (nested.size > 1) "2중 압축 전체 ${nested.size}개 풀기" else "2중 압축 풀기")
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

/** 2중 압축 하나: 안에 든 압축 목록 + 꺼낼 이름 고치기 + 바깥 이름 복사. */
@Composable
private fun NestedEditDialog(
    d: ArchiveDoctor.Diagnosis,
    index: Int,
    count: Int,
    names: List<String>,
    onNames: (List<String>) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onUnnestOne: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val outerBase = ArchiveDoctor.baseName(d.entry.name)
    var copied by remember(d.entry.path) { mutableStateOf(false) }
    val single = count == 1
    AlertDialog(
        onDismissRequest = onBack,
        title = {
            Text(
                if (single) "2중 압축 확인" else "2중 압축 확인 (${index + 1}/$count)",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("바깥 압축파일", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(d.entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(sizeText(d.entry.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(outerBase))
                        copied = true
                    }) { Text("바깥 이름 복사") }
                    if (d.inner.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = {
                            onNames(List(d.inner.size) { "$outerBase ${it + 1}" })
                        }) { Text("이름+1,2,3") }
                    }
                }
                if (copied) {
                    Text(
                        "복사했어요. 아래 칸을 길게 눌러 붙여넣고 뒤에 번호를 붙이면 돼요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "안에 든 압축 ${d.inner.size}개 → 꺼낼 이름",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                d.inner.forEachIndexed { i, inner ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        inner.name.substringAfterLast('/') + " · " + sizeText(inner.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OutlinedTextField(
                        value = names.getOrElse(i) { "" },
                        onValueChange = { v ->
                            onNames(List(d.inner.size) { j -> if (j == i) v.replace("\n", "") else names.getOrElse(j) { "" } })
                        },
                        singleLine = true,
                        suffix = { Text(".zip") },
                        placeholder = { Text(ArchiveDoctor.defaultNames(d)[i]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "빈 칸이면 원래 이름으로, 같은 이름이 있으면 (2)가 붙어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (single) {
                TextButton(onClick = onUnnestOne) { Text("압축 풀기") }
            } else {
                TextButton(onClick = onNext) { Text(if (index + 1 < count) "다음" else "목록으로") }
            }
        },
        dismissButton = {
            Row {
                if (!single && index > 0) TextButton(onClick = onPrev) { Text("이전") }
                TextButton(onClick = onBack) { Text(if (single) "취소" else "목록") }
            }
        },
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
