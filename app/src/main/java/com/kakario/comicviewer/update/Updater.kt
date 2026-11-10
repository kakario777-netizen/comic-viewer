package com.kakario.comicviewer.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 앱 안 업데이트: GitHub 최신 빌드를 확인해서 받고, 안드로이드 설치 화면을 띄운다.
 * (스토어 앱이 아니라서 마지막 '설치' 버튼은 사람이 한 번 눌러야 함 — 안드로이드 규칙)
 */
class Updater private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Release(val build: Int, val apkUrl: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val build: Int) : State
        data class Available(val release: Release, val current: Int) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    /** 지금 설치된 빌드 번호 (GitHub 자동 빌드 번호와 같음). */
    val currentBuild: Int
        get() = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
            else @Suppress("DEPRECATION") info.versionCode
        }.getOrDefault(0)

    /** 새 버전 확인. quiet = true면 최신일 때·실패했을 때 아무것도 안 띄움 (앱 켤 때 자동 확인용). */
    fun check(quiet: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (!quiet) _state.value = State.Checking
            try {
                val r = fetchLatest()
                val cur = currentBuild
                _state.value = when {
                    r == null -> if (quiet) State.Idle else State.Failed("최신 빌드 정보를 찾지 못했어요")
                    r.build > cur -> State.Available(r, cur)
                    quiet -> State.Idle
                    else -> State.UpToDate(cur)
                }
            } catch (e: Exception) {
                _state.value = if (quiet) State.Idle else State.Failed(
                    "업데이트를 확인하지 못했어요.\n${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    /** 받기 → 다 받으면 설치 화면. */
    fun download(release: Release) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val dir = File(context.cacheDir, "update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "comic-viewer-${release.build}.apk")
                _state.value = State.Downloading(release, 0)
                val conn = open(release.apkUrl)
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = State.Downloading(release, pct)
                            }
                        }
                    }
                }
                _state.value = State.Ready(release, file)
                install(file)
            } catch (e: Exception) {
                _state.value = State.Failed("받다가 끊겼어요. 다시 시도해 주세요. (${e.message ?: e.javaClass.simpleName})")
            }
        }
    }

    /** 설치 화면 띄우기. '출처를 알 수 없는 앱' 허용이 안 돼 있으면 그 설정 화면부터. */
    fun install(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun dismiss() {
        if (job?.isActive != true) _state.value = State.Idle
    }

    private fun fetchLatest(): Release? {
        val conn = open(API)
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        // 릴리스 이름 '최신 빌드 #25' 의 25 = 빌드 번호 = 앱 versionCode
        val build = Regex("#(\\d+)").find(json.optString("name"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return Release(build, a.getString("browser_download_url"), a.optLong("size"))
            }
        }
        return null
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "comic-viewer-app")
        conn.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException(
                if (code == 404) "업데이트 저장소에 접근할 수 없어요 (저장소가 공개인지 확인)" else "응답 $code"
            )
        }
        return conn
    }

    companion object {
        private const val API = "https://api.github.com/repos/kakario777-netizen/comic-viewer/releases/tags/latest"

        @Volatile private var instance: Updater? = null
        fun get(context: Context): Updater =
            instance ?: synchronized(this) { instance ?: Updater(context.applicationContext).also { instance = it } }
    }
}
