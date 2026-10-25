package com.lumatv.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class ContentType { LIVE, MOVIE, SERIES, EPISODE }

data class MediaEntry(
    val id: String,
    val title: String,
    val url: String = "",
    val type: ContentType,
    val group: String = "Other",
    val image: String = "",
    val description: String = "",
    val epgId: String = "",
    val seriesId: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val backdrop: String = "",
    val httpUserAgent: String = "",
    val httpReferrer: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("url", url)
        put("type", type.name); put("group", group); put("image", image)
        put("description", description); put("epgId", epgId); put("seriesId", seriesId)
        put("season", season); put("episode", episode); put("backdrop", backdrop)
        put("httpUserAgent", httpUserAgent); put("httpReferrer", httpReferrer)
    }
    companion object {
        fun fromJson(j: JSONObject) = MediaEntry(
            id = j.optString("id"), title = j.optString("title"),
            url = j.optString("url"),
            type = runCatching { ContentType.valueOf(j.optString("type")) }.getOrDefault(ContentType.LIVE),
            group = j.optString("group", "Other"), image = j.optString("image"),
            description = j.optString("description"), epgId = j.optString("epgId"),
            seriesId = j.optString("seriesId"), season = j.optInt("season"),
            episode = j.optInt("episode"), backdrop = j.optString("backdrop"),
            httpUserAgent = j.optString("httpUserAgent"), httpReferrer = j.optString("httpReferrer")
        )
    }
}

data class ProviderConfig(
    val kind: String,
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    val liveFormat: String = "m3u8"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind); put("name", name); put("url", url)
        put("username", username); put("password", password); put("epgUrl", epgUrl)
        put("liveFormat", liveFormat)
    }
    companion object {
        fun fromJson(j: JSONObject) = ProviderConfig(
            kind = j.optString("kind"), name = j.optString("name"),
            url = j.optString("url"), username = j.optString("username"),
            password = j.optString("password"), epgUrl = j.optString("epgUrl"),
            liveFormat = j.optString("liveFormat", "m3u8")
        )
    }
}

data class Programme(val title: String, val starts: Long, val ends: Long)
data class Schedule(val now: Programme? = null, val next: Programme? = null)

object AppData {
    @Volatile var entries: List<MediaEntry> = emptyList()
    @Volatile var schedules: Map<String, Schedule> = emptyMap()
    @Volatile var config: ProviderConfig? = null
    // In-memory ordered episodes supplied by the series screen for uninterrupted playback.
    @Volatile var episodeQueue: List<MediaEntry> = emptyList()
    @Volatile var episodeQueueFor: String = ""
    fun find(id: String): MediaEntry? = entries.firstOrNull { it.id == id }
}

fun makeId(vararg segments: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(segments.joinToString("|").toByteArray(Charsets.UTF_8))
    return bytes.take(12).joinToString("") { "%02x".format(it) }
}

fun JSONArray.asObjects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
