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
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
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
 * 2중 압축 파일을 누르면 하나씩 확인하고 방식(한 파일로 합치기 / 각각 꺼내기)·이름을 고를 수 있다.
 */
@Composable
fun ArchiveReportDialog(
    report: ArchiveDoctor.Report,
    doctor: ArchiveDoctor,
    onUnnest: (only: Set<String>?) -> Unit,
    onMoveBroken: () -> Unit,
    onClose: () -> Unit,
) {
    val nested = report.nested
    val broken = report.broken
    val unknown = report.unknown
    var editing by remember(report.key) { mutableStateOf<Int?>(null) }

    val idx = editing?.takeIf { it in nested.indices }
    if (idx != null) {
        val d = nested[idx]
        NestedEditDialog(
            d = d,
            index = idx,
            count = nested.size,
            plan = doctor.planOf(d),
            onPlan = { doctor.plans[d.entry.path] = it },
            onPrev = { editing = idx - 1 },
            onNext = { editing = if (idx + 1 < nested.size) idx + 1 else null },
            onBack = { editing = null },
            onRunThis = { onUnnest(setOf(d.entry.path)) },
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
                            "2중 압축 파일을 누르면 하나씩 확인하고 방식·이름을 고르고 따로 풀 수 있어요.",
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
                                    val p = doctor.planOf(d)
                                    Text(
                                        if (p.mode == ArchiveDoctor.Mode.MERGE)
                                            "→ 한 파일로 합치기: ${p.fileName}.zip (폴더 ${d.inner.size}개)"
                                        else "→ 각각 꺼내기: " + p.fileNames.joinToString(", ") { "$it.zip" },
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
                            "원본은 폴더 안 숨김 폴더(${ArchiveDoctor.ORIGINAL_DIR})에 보관돼요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (nested.size > 1) {
                            OutlinedButton(onClick = { editing = 0 }, modifier = Modifier.fillMaxWidth()) {
                                Text("하나씩 확인·고치기")
                            }
                        }
                        OutlinedButton(onClick = { onUnnest(null) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (nested.size > 1) "2중 압축 전체 ${nested.size}개 처리" else "2중 압축 처리")
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

/** 2중 압축 하나: 안에 든 압축 목록 + 방식 고르기 + 이름 고치기 + 바깥 이름 복사 + 이 파일만 처리. */
@Composable
private fun NestedEditDialog(
    d: ArchiveDoctor.Diagnosis,
    index: Int,
    count: Int,
    plan: ArchiveDoctor.NestPlan,
    onPlan: (ArchiveDoctor.NestPlan) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onRunThis: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val outerBase = ArchiveDoctor.baseName(d.entry.name)
    var copied by remember(d.entry.path) { mutableStateOf(false) }
    val merge = plan.mode == ArchiveDoctor.Mode.MERGE
    val defaults = ArchiveDoctor.defaultPlan(d)
    AlertDialog(
        onDismissRequest = onBack,
        title = {
            Text(
                if (count == 1) "2중 압축 확인" else "2중 압축 확인 (${index + 1}/$count)",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("바깥 압축파일", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(d.entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "${sizeText(d.entry.size)} · 안에 압축 ${d.inner.size}개",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(outerBase))
                        copied = true
                    }) { Text("바깥 이름 복사") }
                    if (d.inner.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = {
                            val numbered = List(d.inner.size) { "$outerBase ${it + 1}" }
                            onPlan(if (merge) plan.copy(folderNames = List(d.inner.size) { "${it + 1}" }) else plan.copy(fileNames = numbered))
                        }) { Text(if (merge) "폴더 1,2,3" else "이름+1,2,3") }
                    }
                }
                if (copied) {
                    Text(
                        "복사했어요. 칸을 길게 눌러 붙여넣고 뒤에 번호를 붙이면 돼요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // 방식 고르기
                Spacer(Modifier.height(12.dp))
                Text("처리 방식", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ModeOption(
                    selected = merge,
                    title = "한 압축파일로 합치기 (폴더별)" + if (d.inner.size > 1) " · 기본" else "",
                    desc = "압축파일은 하나 그대로, 안쪽 1·2·3이 폴더로 나뉘어 들어가요. 이어서 한 번에 볼 수 있어요.",
                    onClick = { onPlan(plan.copy(mode = ArchiveDoctor.Mode.MERGE)) },
                )
                ModeOption(
                    selected = !merge,
                    title = "각각 압축파일로 꺼내기",
                    desc = "안쪽 압축을 따로따로 파일로 꺼내요.",
                    onClick = { onPlan(plan.copy(mode = ArchiveDoctor.Mode.SPLIT)) },
                )

                Spacer(Modifier.height(10.dp))
                if (merge) {
                    Text("합친 파일 이름", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = plan.fileName,
                        onValueChange = { onPlan(plan.copy(fileName = it.replace("\n", ""))) },
                        singleLine = true,
                        suffix = { Text(".zip") },
                        placeholder = { Text(defaults.fileName) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "안쪽 압축 ${d.inner.size}개 → 폴더 이름",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "안쪽 압축 ${d.inner.size}개 → 꺼낼 파일 이름",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val list = if (merge) plan.folderNames else plan.fileNames
                val defList = if (merge) defaults.folderNames else defaults.fileNames
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
                        value = list.getOrElse(i) { "" },
                        onValueChange = { v ->
                            val next = List(d.inner.size) { j -> if (j == i) v.replace("\n", "") else list.getOrElse(j) { "" } }
                            onPlan(if (merge) plan.copy(folderNames = next) else plan.copy(fileNames = next))
                        },
                        singleLine = true,
                        suffix = { Text(if (merge) "/" else ".zip") },
                        placeholder = { Text(defList.getOrElse(i) { "" }) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "빈 칸이면 원래 이름으로, 같은 이름이 있으면 (2)가 붙어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onRunThis, modifier = Modifier.fillMaxWidth()) {
                    Text(if (merge) "이 파일만 지금 합치기" else "이 파일만 지금 꺼내기")
                }
            }
        },
        confirmButton = {
            if (count > 1) TextButton(onClick = onNext) { Text(if (index + 1 < count) "다음" else "목록으로") }
        },
        dismissButton = {
            Row {
                if (count > 1 && index > 0) TextButton(onClick = onPrev) { Text("이전") }
                TextButton(onClick = onBack) { Text(if (count > 1) "목록" else "닫기") }
            }
        },
    )
}

@Composable
private fun ModeOption(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
