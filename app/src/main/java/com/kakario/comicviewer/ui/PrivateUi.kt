package com.kakario.comicviewer.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kakario.comicviewer.privacy.PrivateMode

/** 이 폰에서 지문(생체 인증)을 쓸 수 있는지. */
fun canUseFingerprint(activity: FragmentActivity): Boolean =
    BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

/** 지문 창 띄우기. 성공하면 onSuccess, 취소하거나 실패하면 onFail(비밀번호로). */
fun askFingerprint(activity: FragmentActivity, onSuccess: () -> Unit, onFail: () -> Unit) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail()
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("개인모드")
            .setSubtitle("지문으로 열기")
            .setNegativeButtonText("비밀번호 입력")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
            .build()
    )
}

/** 개인모드 창들 (자물쇠 버튼을 누르면). */
@Composable
fun PrivateModeDialogs(
    activity: FragmentActivity,
    pm: PrivateMode,
    unlocked: Boolean,
    onDismiss: () -> Unit,
) {
    // 처음: 비밀번호 정하기 / 잠김: 비밀번호 입력 / 열림: 메뉴
    var step by remember {
        mutableStateOf(
            when {
                !pm.hasPin -> Step.SETUP
                unlocked -> Step.MENU
                else -> Step.UNLOCK
            }
        )
    }
    // 잠긴 상태로 열었고 지문을 켜 뒀으면 지문 창부터
    LaunchedEffect(Unit) {
        if (step == Step.UNLOCK && pm.fingerprintEnabled && canUseFingerprint(activity)) {
            askFingerprint(activity, onSuccess = { pm.unlock(); onDismiss() }, onFail = {})
        }
    }
    when (step) {
        Step.SETUP -> NewPinDialog(
            title = "개인모드 비밀번호 정하기",
            hint = "숫자 4~8자리. 숨긴 폴더·파일은 개인모드를 켰을 때만 보여요.",
            onDone = { pin ->
                pm.setPin(pin)
                pm.unlock()
                step = if (canUseFingerprint(activity)) Step.ASK_FP else Step.DONE
            },
            onDismiss = onDismiss,
        )
        Step.ASK_FP -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("지문으로도 열까요?") },
            text = { Text("다음부터 자물쇠를 누르면 지문으로 바로 열 수 있어요. 비밀번호도 그대로 쓸 수 있어요.") },
            confirmButton = {
                TextButton(onClick = {
                    askFingerprint(activity, onSuccess = { pm.fingerprintEnabled = true; onDismiss() }, onFail = { onDismiss() })
                }) { Text("지문 사용") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("안 함") } },
        )
        Step.UNLOCK -> PinDialog(
            title = "개인모드 열기",
            pm = pm,
            showFingerprint = pm.fingerprintEnabled && canUseFingerprint(activity),
            onFingerprint = { askFingerprint(activity, onSuccess = { pm.unlock(); onDismiss() }, onFail = {}) },
            onOk = { pm.unlock(); onDismiss() },
            onDismiss = onDismiss,
        )
        Step.MENU -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("개인모드 켜짐") },
            text = {
                Column {
                    Text(
                        "숨긴 폴더·파일이 흐리게 보이고 있어요. 앱을 나가면 자동으로 다시 잠겨요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { pm.lock(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text("개인모드 끄기 (잠그기)")
                    }
                    OutlinedButton(onClick = { step = Step.CHANGE_OLD }, modifier = Modifier.fillMaxWidth()) {
                        Text("비밀번호 바꾸기")
                    }
                    if (canUseFingerprint(activity)) {
                        var fp by remember { mutableStateOf(pm.fingerprintEnabled) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(AppIcons.Fingerprint, size = 20.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("지문으로 열기", modifier = Modifier.weight(1f))
                            Switch(checked = fp, onCheckedChange = { on ->
                                if (on) askFingerprint(activity, onSuccess = { pm.fingerprintEnabled = true; fp = true }, onFail = {})
                                else { pm.fingerprintEnabled = false; fp = false }
                            })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        )
        Step.CHANGE_OLD -> PinDialog(
            title = "지금 비밀번호",
            pm = pm,
            showFingerprint = false,
            onFingerprint = {},
            onOk = { step = Step.CHANGE_NEW },
            onDismiss = onDismiss,
        )
        Step.CHANGE_NEW -> NewPinDialog(
            title = "새 비밀번호",
            hint = "숫자 4~8자리",
            onDone = { pin -> pm.setPin(pin); step = Step.DONE },
            onDismiss = onDismiss,
        )
        Step.DONE -> LaunchedEffect(Unit) { onDismiss() }
    }
}

private enum class Step { SETUP, ASK_FP, UNLOCK, MENU, CHANGE_OLD, CHANGE_NEW, DONE }

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String, focus: FocusRequester? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(8)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth().let { m -> if (focus != null) m.focusRequester(focus) else m },
    )
}

/** 비밀번호 확인 창. */
@Composable
private fun PinDialog(
    title: String,
    pm: PrivateMode,
    showFingerprint: Boolean,
    onFingerprint: () -> Unit,
    onOk: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun submit() {
        val wait = pm.waitSeconds()
        when {
            wait > 0 -> error = "여러 번 틀려서 ${wait}초 뒤에 다시 해 주세요"
            pm.checkPin(pin) -> onOk()
            else -> {
                pin = ""
                val w = pm.waitSeconds()
                error = if (w > 0) "여러 번 틀려서 ${w}초 뒤에 다시 해 주세요" else "비밀번호가 맞지 않아요"
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                PinField(pin, { pin = it; error = null }, "비밀번호 (숫자 4~8자리)", focus)
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (showFingerprint) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onFingerprint, modifier = Modifier.fillMaxWidth()) {
                        AppIcon(AppIcons.Fingerprint, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("지문으로 열기")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = PrivateMode.isValidPin(pin)) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 새 비밀번호 정하기 (두 번 입력). */
@Composable
private fun NewPinDialog(title: String, hint: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                PinField(pin, { pin = it; error = null }, "비밀번호")
                Spacer(Modifier.height(6.dp))
                PinField(again, { again = it; error = null }, "한 번 더")
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "비밀번호를 잊으면 앱 데이터를 지워야만 초기화돼요 (숨긴 목록도 함께 지워짐).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = PrivateMode.isValidPin(pin) && again.isNotEmpty(),
                onClick = { if (pin == again) onDone(pin) else { error = "두 번 입력한 비밀번호가 달라요"; again = "" } },
            ) { Text("정하기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
