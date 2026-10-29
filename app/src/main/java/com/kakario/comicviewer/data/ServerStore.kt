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
)

/** 서버 목록을 폰 안(SharedPreferences)에 저장/불러오기. */
class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)

    fun load(): List<FtpServer> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
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
                    password = o.optString("password"),
                    encoding = o.optString("encoding", "UTF-8"),
                )
            }
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
                    .put("password", s.password)
                    .put("encoding", s.encoding)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "server_list"
    }
}
