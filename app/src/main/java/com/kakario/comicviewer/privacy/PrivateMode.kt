package com.kakario.comicviewer.privacy

import android.content.Context
import android.util.Base64
import com.kakario.comicviewer.data.SecretBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 개인모드: 숨긴 폴더·파일은 개인모드를 켰을 때만 보인다.
 *  - 비밀번호: 숫자 4~8자리 (원문은 저장 안 하고 PBKDF2 해시만)
 *  - 지문으로도 열 수 있음
 *  - 숨긴 목록은 보안 저장소 키로 암호화해서 저장
 *  - 앱이 뒤로 가면(화면 꺼짐·다른 앱) 자동으로 잠김
 */
class PrivateMode private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("private_mode", Context.MODE_PRIVATE)

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    private val _hidden = MutableStateFlow(loadHidden())
    /** 숨긴 항목: "출처|경로" (출처 = local 또는 ftp<서버id>) */
    val hidden: StateFlow<Set<String>> = _hidden

    val hasPin: Boolean get() = prefs.contains(KEY_HASH)

    var fingerprintEnabled: Boolean
        get() = prefs.getBoolean(KEY_FP, false)
        set(v) { prefs.edit().putBoolean(KEY_FP, v).apply() }

    // ───────── 비밀번호

    fun setPin(pin: String) {
        require(isValidPin(pin))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt(KEY_FAILS, 0)
            .apply()
    }

    /** 몇 초 더 기다려야 하는지 (틀린 횟수가 많을 때). 0이면 바로 입력 가능. */
    fun waitSeconds(): Int {
        val until = prefs.getLong(KEY_UNTIL, 0L)
        val left = ((until - System.currentTimeMillis()) / 1000).toInt()
        return if (left > 0) left else 0
    }

    fun checkPin(pin: String): Boolean {
        if (waitSeconds() > 0) return false
        val salt = prefs.getString(KEY_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val want = prefs.getString(KEY_HASH, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val ok = MessageDigest.isEqual(hash(pin, salt), want)
        if (ok) {
            prefs.edit().putInt(KEY_FAILS, 0).apply()
        } else {
            val fails = prefs.getInt(KEY_FAILS, 0) + 1
            val e = prefs.edit().putInt(KEY_FAILS, fails)
            // 5번 틀리면 30초, 그 뒤로 틀릴 때마다 2배씩 (최대 10분)
            if (fails >= 5) {
                val sec = minOf(600L, 30L shl minOf(5, fails - 5))
                e.putLong(KEY_UNTIL, System.currentTimeMillis() + sec * 1000)
            }
            e.apply()
        }
        return ok
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 60_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    // ───────── 켜기 / 끄기

    fun unlock() { _unlocked.value = true }
    fun lock() { _unlocked.value = false }

    // ───────── 숨기기

    fun key(tag: String, path: String) = "$tag|${path.trimEnd('/').ifEmpty { "/" }}"

    /** 이 항목이나 그 위 폴더 중 하나라도 숨겨져 있으면 true. */
    fun isHidden(tag: String, path: String): Boolean {
        val set = _hidden.value
        if (set.isEmpty()) return false
        var p = path.trimEnd('/')
        while (p.isNotEmpty()) {
            if ("$tag|$p" in set) return true
            val i = p.lastIndexOf('/')
            if (i <= 0) break
            p = p.substring(0, i)
        }
        return false
    }

    /** 지금 화면에서 가려야 하는지 (개인모드가 꺼져 있고 숨긴 항목이면). */
    fun shouldHide(tag: String, path: String) = !_unlocked.value && isHidden(tag, path)

    fun hide(tag: String, path: String) = update { it + key(tag, path) }
    fun unhide(tag: String, path: String) = update { it - key(tag, path) }
    fun isHiddenExactly(tag: String, path: String) = key(tag, path) in _hidden.value

    private fun update(f: (Set<String>) -> Set<String>) {
        val next = f(_hidden.value)
        _hidden.value = next
        val json = JSONArray().apply { next.forEach { put(it) } }.toString()
        prefs.edit().putString(KEY_HIDDEN, SecretBox.encrypt(json)).apply()
    }

    private fun loadHidden(): Set<String> {
        val raw = prefs.getString(KEY_HIDDEN, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(SecretBox.decrypt(raw))
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    companion object {
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_FAILS = "pin_fails"
        private const val KEY_UNTIL = "pin_wait_until"
        private const val KEY_FP = "fingerprint"
        private const val KEY_HIDDEN = "hidden"

        fun isValidPin(pin: String) = pin.length in 4..8 && pin.all { it.isDigit() }

        @Volatile private var instance: PrivateMode? = null
        fun get(context: Context): PrivateMode =
            instance ?: synchronized(this) { instance ?: PrivateMode(context.applicationContext).also { instance = it } }
    }
}
