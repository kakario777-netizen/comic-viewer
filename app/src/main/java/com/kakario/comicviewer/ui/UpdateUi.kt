package com.kakario.comicviewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kakario.comicviewer.update.Updater

/** 업데이트 알림·받는 중·설치 안내 창 (앱 어디서나 같은 창). */
@Composable
fun UpdateDialog(state: Updater.State, updater: Updater) {
    when (state) {
        is Updater.State.Available -> AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("새 버전이 있어요") },
            text = { Text("지금 빌드 #${state.current} → 새 빌드 #${state.release.build}\n받아서 바로 설치할까요?") },
            confirmButton = { TextButton(onClick = { updater.download(state.release) }) { Text("업데이트") } },
            dismissButton = { TextButton(onClick = { updater.dismiss() }) { Text("나중에") } },
        )
        is Updater.State.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("받는 중 ${state.percent}%") },
            text = {
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {},
        )
        is Updater.State.Ready -> AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("설치 준비 완료") },
            text = {
                Column {
                    Text("설치 화면에서 '업데이트'를 눌러 주세요.")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "처음 한 번은 '이 출처 허용'을 켜야 할 수 있어요. 켠 뒤 돌아와서 아래 '설치'를 눌러 주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { updater.install(state.file) }) { Text("설치") } },
            dismissButton = { TextButton(onClick = { updater.dismiss() }) { Text("닫기") } },
        )
        is Updater.State.UpToDate -> AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("최신 버전이에요") },
            text = { Text("지금 빌드 #${state.build}") },
            confirmButton = { TextButton(onClick = { updater.dismiss() }) { Text("확인") } },
        )
        is Updater.State.Failed -> AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("업데이트") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = { updater.dismiss() }) { Text("확인") } },
        )
        Updater.State.Checking, Updater.State.Idle -> Unit
    }
}
