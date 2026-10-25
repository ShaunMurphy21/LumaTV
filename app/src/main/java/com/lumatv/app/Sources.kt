package com.lumatv.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

object SourceService {
    fun epgUrl(config: ProviderConfig): String {
        if (config.epgUrl.isNotBlank()) return config.epgUrl
        if (config.kind != "xtream") return ""
        return "${config.url.trimEnd('/')}/xmltv.php?username=${Net.urlEncode(config.username)}&password=${Net.urlEncode(config.password)}"
    }

    private val metadata = Regex("([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*\"([^\"]*)\"")

    fun load(context: Context, provider: ProviderConfig): List<MediaEntry> = when (provider.kind) {
        "demo" -> demo()
        "m3u" -> {
            val (connection, stream) = Net.open(provider.url, 30_000)
            try { stream.use { parseM3u(it) } } finally { connection.disconnect() }
        }
        "file" -> context.contentResolver.openInputStream(Uri.parse(provider.url))?.use { parseM3u(it) }
            ?: error("Cannot access that playlist file. Import it again.")
        "xtream" -> loadXtream(provider)
        else -> error("Unsupported provider type")
    }

    fun parseM3u(input: InputStream): List<MediaEntry> {
        val entries = ArrayList<MediaEntry>()
        var title = ""
        var logo = ""
        var epgId = ""
        var group = "Other"
        var agent = ""
        var referrer = ""
        var sawHeader = false
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        var readLines = 0
        reader.forEachLine { raw ->
            readLines++
            if (readLines > 200_000) error("Playlist contains too many lines (limit 200,000).")
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> sawHeader = true
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    sawHeader = true
                    val attrs = metadata.findAll(line.substringBeforeLast(',', line)).associate {
                        it.groupValues[1].lowercase() to it.groupValues[2]
                    }
                    title = line.substringAfterLast(',', "").trim()
                    logo = attrs["tvg-logo"].orEmpty()
                    epgId = attrs["tvg-id"].orEmpty()
                    group = attrs["group-title"]?.ifBlank { "Other" } ?: "Other"
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').ifBlank { "Other" }
                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> agent = line.substringAfter('=')
                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) -> referrer = line.substringAfter('=')
                line.isNotEmpty() && !line.startsWith("#") -> {
                    if (!Net.validHttp(line)) return@forEachLine
                    if (entries.size >= 50_000) error("Playlist exceeds the 50,000-item safety limit.")
                    val path = runCatching { Uri.parse(line).path.orEmpty().lowercase() }.getOrDefault("")
                    val lowerGroup = group.lowercase()
                    val type = when {
                        "/series/" in path || "series" in lowerGroup && !path.endsWith(".m3u8") -> ContentType.MOVIE
                        "/movie/" in path || path.endsWith(".mp4") || path.endsWith(".mkv") ||
                            path.endsWith(".avi") || "vod" in lowerGroup || "movie" in lowerGroup -> ContentType.MOVIE
                        else -> ContentType.LIVE
                    }
                    val display = title.ifBlank { Uri.parse(line).lastPathSegment.orEmpty().ifBlank { "Untitled" } }
                    entries.add(MediaEntry(makeId(line, display), display, line, type, group, logo, epgId = epgId,
                        httpUserAgent = agent, httpReferrer = referrer))
                    title = ""; logo = ""; epgId = ""; group = "Other"; agent = ""; referrer = ""
                }
            }
        }
        if (!sawHeader || entries.isEmpty()) error("No playable HTTP/HTTPS items were found in this M3U playlist.")
        return entries.distinctBy { it.id }
    }

    private fun api(config: ProviderConfig, action: String = "", seriesId: String = ""): String {
        val base = config.url.trim().trimEnd('/')
        val params = mutableListOf(
            "username=${Net.urlEncode(config.username)}",
            "password=${Net.urlEncode(config.password)}"
        )
        if (action.isNotBlank()) params.add("action=${Net.urlEncode(action)}")
        if (seriesId.isNotBlank()) params.add("series_id=${Net.urlEncode(seriesId)}")
        return "$base/player_api.php?${params.joinToString("&")}" 
    }

    private fun array(config: ProviderConfig, action: String): List<JSONObject> =
        JSONArray(Net.readText(api(config, action), 40_000_000)).asObjects()

    private fun loadXtream(config: ProviderConfig): List<MediaEntry> {
        require(Net.validHttp(config.url)) { "Enter a full http:// or https:// server address." }
        val status = JSONObject(Net.readText(api(config), 1_000_000))
        val auth = status.optJSONObject("user_info") ?: error("The server did not return valid account information.")
        if (auth.optString("auth") != "1") error("The provider rejected the login details.")
        val liveGroups = array(config, "get_live_categories").associate {
            it.optString("category_id") to it.optString("category_name", "Live TV")
        }
        val movieGroups = array(config, "get_vod_categories").associate {
            it.optString("category_id") to it.optString("category_name", "Films")
        }
        val seriesGroups = array(config, "get_series_categories").associate {
            it.optString("category_id") to it.optString("category_name", "Series")
        }
        val base = config.url.trimEnd('/')
        val u = Net.urlEncode(config.username)
        val p = Net.urlEncode(config.password)
        val live = array(config, "get_live_streams").map { j ->
            val sid = j.optString("stream_id")
            val extension = if (config.liveFormat == "ts") "ts" else "m3u8"
            MediaEntry(
                id = makeId("live", base, sid), title = j.optString("name", "Channel"),
                url = "$base/live/$u/$p/$sid.$extension", type = ContentType.LIVE,
                group = liveGroups[j.optString("category_id")] ?: "Live TV",
                image = j.optString("stream_icon"), epgId = j.optString("epg_channel_id")
            )
        }
        val vod = array(config, "get_vod_streams").map { j ->
            val sid = j.optString("stream_id")
            val ext = j.optString("container_extension", "mp4").let { if (it.matches(Regex("[a-zA-Z0-9]{2,5}"))) it else "mp4" }
            MediaEntry(
                id = makeId("movie", base, sid), title = j.optString("name", "Film"),
                url = "$base/movie/$u/$p/$sid.$ext", type = ContentType.MOVIE,
                group = movieGroups[j.optString("category_id")] ?: "Films",
                image = j.optString("stream_icon"), description = j.optString("plot"),
                backdrop = j.optString("cover_big")
            )
        }
        val shows = array(config, "get_series").map { j ->
            val sid = j.optString("series_id")
            MediaEntry(
                id = makeId("series", base, sid), title = j.optString("name", "Show"),
                type = ContentType.SERIES, group = seriesGroups[j.optString("category_id")] ?: "Series",
                image = j.optString("cover"), description = j.optString("plot"),
                seriesId = sid, backdrop = j.optJSONArray("backdrop")?.optString(0)
                    ?: j.optString("backdrop")
            )
        }
        val all = live + vod + shows
        if (all.isEmpty()) error("This account returned no channels, films or shows.")
        return all
    }

    fun episodes(config: ProviderConfig, show: MediaEntry): List<MediaEntry> {
        require(config.kind == "xtream") { "Series episodes require an Xtream-compatible provider." }
        val obj = JSONObject(Net.readText(api(config, "get_series_info", show.seriesId), 20_000_000))
        val seasons = obj.optJSONObject("episodes") ?: error("No episodes were returned for this series.")
        val result = mutableListOf<MediaEntry>()
        val base = config.url.trimEnd('/')
        val u = Net.urlEncode(config.username)
        val p = Net.urlEncode(config.password)
        val keys = seasons.keys()
        while (keys.hasNext()) {
            val seasonKey = keys.next()
            val episodes = seasons.optJSONArray(seasonKey) ?: continue
            episodes.asObjects().forEach { j ->
                val id = j.optString("id")
                val ext = j.optString("container_extension", "mp4")
                    .let { if (it.matches(Regex("[A-Za-z0-9]{2,5}"))) it else "mp4" }
                val info = j.optJSONObject("info")
                val season = seasonKey.toIntOrNull() ?: 0
                val number = j.optInt("episode_num")
                result.add(MediaEntry(
                    id = makeId("episode", base, id),
                    title = j.optString("title").ifBlank { "Episode $number" },
                    url = "$base/series/$u/$p/$id.$ext", type = ContentType.EPISODE,
                    group = show.title, image = info?.optString("movie_image")?.ifBlank { show.image } ?: show.image,
                    description = info?.optString("plot") ?: "", seriesId = show.seriesId,
                    season = season, episode = number
                ))
            }
        }
        return result.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    fun demo(): List<MediaEntry> = listOf(
        MediaEntry("demo_live", "Sample HLS stream", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            ContentType.LIVE, "Demo streams", description = "Public player test stream"),
        MediaEntry("demo_bunny", "Big Buck Bunny", "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            ContentType.MOVIE, "Sample films", "https://storage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg",
            "Animated short film used for playback testing"),
        MediaEntry("demo_dream", "Elephants Dream", "https://storage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
            ContentType.MOVIE, "Sample films", "https://storage.googleapis.com/gtv-videos-bucket/sample/images/ElephantsDream.jpg",
            "Animated short film used for playback testing")
    )
}
