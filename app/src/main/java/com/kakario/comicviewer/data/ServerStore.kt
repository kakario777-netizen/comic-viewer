package com.kakario.comicviewer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** FTP 서버 접속 정보. */
data class FtpServer(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    /** "UTF-8" 또는 "EUC-KR" — 한글 파일명이 깨지면 EUC-KR로 바꿔 쓴다. */
    val encoding: String = "UTF-8",
    /** FTPS(보안 연결) 사용 */
    val secure: Boolean = false,
) {
    // 로그·오류에 비밀번호가 찍히지 않게
    override fun toString() = "FtpServer(id=$id, name=$name)"
}

/** 서버 목록을 폰 안(SharedPreferences)에 저장/불러오기. */
class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)

    fun load(): List<FtpServer> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        var legacy = false
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FtpServer(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = o.getInt("port"),
                    user = o.optString("user"),
                    password = if (o.has("pw")) SecretBox.decrypt(o.optString("pw"))
                    else o.optString("password").also { if (it.isNotEmpty()) legacy = true },
                    encoding = o.optString("encoding", "UTF-8"),
                    secure = o.optBoolean("secure", false),
                )
            }.also { if (legacy) save(it) } // 예전 평문 비밀번호 → 암호화해서 다시 저장
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(servers: List<FtpServer>) {
        val arr = JSONArray()
        servers.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("host", s.host)
                    .put("port", s.port)
                    .put("user", s.user)
                    .put("pw", SecretBox.encrypt(s.password)) // 보안 저장소 키로 암호화
                    .put("encoding", s.encoding)
                    .put("secure", s.secure)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "server_list"
    }
}
