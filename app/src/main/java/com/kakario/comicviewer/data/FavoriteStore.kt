package com.kakario.comicviewer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 즐겨찾기한 폴더. serverId가 null이면 내 폰(로컬) 폴더. */
data class Favorite(
    val id: Long,
    val serverId: Long?,
    val path: String,
    val name: String,
) {
    val isLocal: Boolean get() = serverId == null

    fun matches(serverId: Long?, path: String): Boolean =
        this.serverId == serverId && this.path.trimEnd('/') == path.trimEnd('/')
}

class FavoriteStore(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun load(): List<Favorite> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Favorite(
                    id = o.getLong("id"),
                    serverId = if (o.isNull("serverId")) null else o.getLong("serverId"),
                    path = o.getString("path"),
                    name = o.getString("name"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(favorites: List<Favorite>) {
        val arr = JSONArray()
        favorites.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("serverId", f.serverId ?: JSONObject.NULL)
                    .put("path", f.path)
                    .put("name", f.name)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "favorite_list"
    }
}
