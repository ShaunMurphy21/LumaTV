package com.lumatv.app

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SourceService {
    fun epgUrl(config: ProviderConfig): String {
        if (config.epgUrl.isNotBlank()) return config.epgUrl
        if (config.kind != "xtream") return ""
        return "${config.url.trimEnd('/')}/xmltv.php?username=${Net.urlEncode(config.username)}&password=${Net.urlEncode(config.password)}"
    }

    private val metadata = Regex("([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*\"([^\"]*)\"")

    data class CatalogLoad(val entries: List<MediaEntry>, val report: String)

    fun load(context: Context, provider: ProviderConfig): List<MediaEntry> =
        loadDetailed(context, provider).entries

    fun loadDetailed(context: Context, provider: ProviderConfig): CatalogLoad = when (provider.kind) {
        "demo" -> summary(demo(), "Demo catalogue")
        "m3u" -> {
            val (connection, stream) = Net.open(provider.url, 30_000)
            val playlist = try { stream.use { parseM3u(it) } } finally { connection.disconnect() }
            enrichM3u(provider, playlist)
        }
        "file" -> {
            val items = context.contentResolver.openInputStream(Uri.parse(provider.url))?.use { parseM3u(it) }
                ?: error("Cannot access that playlist file. Import it again.")
            summary(items, "Local M3U file: only content listed in the file can be imported")
        }
        "xtream" -> loadXtream(provider)
        else -> error("Unsupported provider type")
    }

    private fun summary(items: List<MediaEntry>, label: String): CatalogLoad = CatalogLoad(items,
        "$label\nLive TV: ${items.count { it.type == ContentType.LIVE }}\n" +
        "Films: ${items.count { it.type == ContentType.MOVIE }}\n" +
        "Series: ${items.count { it.type == ContentType.SERIES }}")

    // Many M3U links use the same credentials as Xtream. A live-only M3U export
    // can therefore still expose its VOD catalogue via player_api.php.
    // Attempt discovery ONLY for the standard get.php-style URL with embedded credentials.
    private fun enrichM3u(provider: ProviderConfig, playlist: List<MediaEntry>): CatalogLoad {
        val baseReport = summary(playlist, "M3U playlist").report
        val hasMovies = playlist.any { it.type == ContentType.MOVIE }
        val hasSeries = playlist.any { it.type == ContentType.SERIES }
        if (hasMovies && hasSeries) return CatalogLoad(playlist, baseReport)
        val xtream = xtreamFromM3u(provider) ?: return CatalogLoad(
            playlist, "$baseReport\nNo Xtream login could be derived from this M3U URL. " +
                "If your provider offers Xtream login details, connect using the Xtream option to import films and series.")
        return try {
            val extra = loadXtream(xtream, loadLive = false)
            // Do not rescan a 30,000-channel M3U for every single film/series item.
            // That quadratic work could leave the spinner stuck for minutes.
            val additional = extra.entries.filter {
                (it.type == ContentType.MOVIE && !hasMovies) ||
                (it.type == ContentType.SERIES && !hasSeries)
            }
            val combined = (playlist + additional).distinctBy { it.id }
            CatalogLoad(combined, summary(combined, "M3U + automatic Xtream VOD discovery").report +
                "\n\nXtream discovery:\n" + extra.report)
        } catch (e: Exception) {
            CatalogLoad(playlist, "$baseReport\nXtream discovery unavailable: ${safeFailure(e)}. " +
                "Use an Xtream provider login if available.")
        }
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
        // Common episode notation in IPTV playlists: Title S01E02 / 1x02 / Season 1 Episode 2.
        val episodePattern = Regex("(?i)\\bS(\\d{1,2})[ ._-]*E(\\d{1,3})\\b|\\b(\\d{1,2})x(\\d{1,3})\\b|\\bSeason[ ._-]*(\\d{1,2})[ ._-]*Episode[ ._-]*(\\d{1,3})\\b")
        val series = linkedMapOf<String, MediaEntry>()
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
                    val display = title.ifBlank { Uri.parse(line).lastPathSegment.orEmpty().ifBlank { "Untitled" } }
                    val seriesGroup = "/series/" in path || "series" in lowerGroup || "tv shows" in lowerGroup
                    val movieGroup = "/movie/" in path || listOf("movie", "film", "vod", "cinema").any { it in lowerGroup }
                    val episodeMatch = episodePattern.find(display)
                    val type = when {
                        seriesGroup || (episodeMatch != null && !movieGroup) -> ContentType.EPISODE
                        movieGroup || listOf(".mp4", ".mkv", ".avi", ".mov").any { path.endsWith(it) } -> ContentType.MOVIE
                        else -> ContentType.LIVE
                    }
                    if (type == ContentType.EPISODE) {
                        val name = if (episodeMatch != null) SeriesVariants.episodeSeriesTitle(
                            display, episodeMatch.range.first, episodeMatch.range.last + 1
                        ) else display
                        val seriesKey = makeId("playlist-series", group.lowercase(), name.lowercase())
                        val season = episodeMatch?.let { listOf(1, 3, 5).firstNotNullOfOrNull { n -> it.groups[n]?.value?.toIntOrNull() } } ?: 1
                        val episode = episodeMatch?.let { listOf(2, 4, 6).firstNotNullOfOrNull { n -> it.groups[n]?.value?.toIntOrNull() } } ?: 1
                        series.putIfAbsent(seriesKey, MediaEntry(seriesKey, name, type = ContentType.SERIES,
                            group = group, image = logo, seriesId = seriesKey))
                        entries.add(MediaEntry(makeId(line, display), display, line, ContentType.EPISODE,
                            group, logo, epgId = epgId, seriesId = seriesKey, season = season, episode = episode,
                            httpUserAgent = agent, httpReferrer = referrer))
                    } else {
                        entries.add(MediaEntry(makeId(line, display), display, line, type, group, logo, epgId = epgId,
                            httpUserAgent = agent, httpReferrer = referrer))
                    }
                    title = ""; logo = ""; epgId = ""; group = "Other"; agent = ""; referrer = ""
                }
            }
        }
        if (!sawHeader || entries.isEmpty()) error("No playable HTTP/HTTPS items were found in this M3U playlist.")
        return (series.values + entries).distinctBy { it.id }
    }

    fun xtreamFor(provider: ProviderConfig): ProviderConfig? = when (provider.kind) {
        "xtream" -> provider
        "m3u" -> xtreamFromM3u(provider)
        else -> null
    }

    private fun xtreamFromM3u(provider: ProviderConfig): ProviderConfig? {
        if (provider.kind != "m3u" || !Net.validHttp(provider.url)) return null
        val uri = Uri.parse(provider.url)
        val endpoint = uri.path.orEmpty().substringAfterLast('/').lowercase()
        if (endpoint !in setOf("get.php", "playlist.php", "m3u.php")) return null
        val username = uri.getQueryParameter("username")?.takeIf { it.isNotBlank() } ?: return null
        val password = uri.getQueryParameter("password")?.takeIf { it.isNotBlank() } ?: return null
        val base = provider.url.substringBefore('?').substringBeforeLast('/')
        return ProviderConfig("xtream", provider.name, base, username, password, provider.epgUrl)
    }

    private fun api(
        config: ProviderConfig, action: String = "", seriesId: String = "", categoryId: String = ""
    ): String {
        val base = config.url.trim().trimEnd('/')
        val params = mutableListOf(
            "username=${Net.urlEncode(config.username)}",
            "password=${Net.urlEncode(config.password)}"
        )
        if (action.isNotBlank()) params.add("action=${Net.urlEncode(action)}")
        if (seriesId.isNotBlank()) params.add("series_id=${Net.urlEncode(seriesId)}")
        if (categoryId.isNotBlank()) params.add("category_id=${Net.urlEncode(categoryId)}")
        return "$base/player_api.php?${params.joinToString("&")}" 
    }

    private data class FetchOutcome(val rows: List<JSONObject>, val note: String)
    // JSON arrays can use many times their wire size once parsed by org.json.
    // Never materialise an unbounded IPTV catalogue in the app process.
    private const val MAX_CATALOGUE_BYTES = 10_000_000
    private const val MAX_ROWS_PER_RESPONSE = 20_000
    private const val MAX_ROWS_ACROSS_CATEGORIES = 35_000

    // Never turn an API failure into an unexplained empty library. Avoid echoing
    // HTTP URLs, usernames or passwords into diagnostic text.
    private fun safeFailure(error: Exception): String = when (error) {
        is java.net.SocketTimeoutException -> "request timed out"
        is java.net.UnknownHostException -> "server could not be resolved"
        is java.net.ConnectException -> "connection refused"
        is org.json.JSONException -> "unexpected response format (not a supported JSON catalogue)"
        else -> {
            val reason = error.message.orEmpty()
            when {
                reason.startsWith("Server returned HTTP ") ->
                    "HTTP ${reason.substringAfter("Server returned HTTP ").takeWhile(Char::isDigit)}"
                reason.contains("exceeds supported limit", true) -> "catalogue too large for a single response"
                else -> "request unavailable (${error.javaClass.simpleName})"
            }
        }
    }

    private fun fetchArray(config: ProviderConfig, action: String, categoryId: String = ""): FetchOutcome {
        return try {
            val maxBytes = when {
                action.endsWith("_categories") -> 2_000_000
                categoryId.isNotBlank() -> 4_000_000
                else -> MAX_CATALOGUE_BYTES
            }
            val payload = Net.readText(api(config, action, categoryId = categoryId), maxBytes).trim()
            val array = when {
                payload.startsWith("[") -> JSONArray(payload)
                payload.startsWith("{") -> {
                    val obj = JSONObject(payload)
                    sequenceOf("data", "results", "items", "streams", "movies", "series", "categories")
                        .mapNotNull { obj.optJSONArray(it) }.firstOrNull()
                        ?: return FetchOutcome(emptyList(), "unsupported catalogue object")
                }
                payload == "[]" -> JSONArray()
                payload.isEmpty() || payload == "null" || payload == "false" ->
                    return FetchOutcome(emptyList(), "no catalogue returned by provider")
                else -> return FetchOutcome(emptyList(), "provider returned a non-JSON catalogue")
            }
            if (array.length() > MAX_ROWS_PER_RESPONSE) {
                return FetchOutcome(emptyList(), "catalogue has too many entries in one response; trying categories")
            }
            val rows = array.asObjects()
            FetchOutcome(rows, "${rows.size} returned")
        } catch (e: Exception) {
            Log.w("LumaTV", "Xtream $action request failed: ${errorKind(e)}")
            FetchOutcome(emptyList(), safeFailure(e))
        }
    }

    private fun errorKind(error: Exception): String = error.javaClass.simpleName

    // If the global VOD/series response is empty, too large or unsupported,
    // retry by category: many provider panels support only category requests.
    private fun fetchCatalogue(
        config: ProviderConfig, action: String, categories: List<JSONObject>,
        maxCategories: Int = 80, skipGlobal: Boolean = false
    ): FetchOutcome {
        // Large Xtream providers can stall on unfiltered get_live_streams.
        // Fetch a few specific categories only when Live is explicitly opened.
        val full = if (skipGlobal)
            FetchOutcome(emptyList(), "global Live TV lookup skipped")
        else fetchArray(config, action)
        if (full.rows.isNotEmpty() || categories.isEmpty() ||
            listOf("HTTP 401", "HTTP 403", "HTTP 429").any { full.note == it }) return full
        val ids = categories.mapNotNull { it.optString("category_id").takeIf(String::isNotBlank) }
            .distinct().take(maxCategories)
        if (ids.isEmpty()) return full

        // The live-TV bootstrap previously waited for 80 serial HTTP calls.
        // Keep at most three requests in flight to improve load time without
        // overwhelming a low-memory Fire TV Stick or the IPTV provider.
        val pool = Executors.newFixedThreadPool(3)
        val queue = ExecutorCompletionService<FetchOutcome>(pool)
        val recovered = ArrayList<JSONObject>()
        var succeeded = 0
        var failures = 0
        var next = 0
        var active = 0
        var processed = 0
        val deadline = System.nanoTime() + 120_000_000_000L
        try {
            while ((next < ids.size || active > 0) && System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted) break
                while (active < 3 && next < ids.size) {
                    val categoryId = ids[next++]
                    queue.submit(Callable { fetchArray(config, action, categoryId) })
                    active++
                }
                val response = queue.take().get()
                active--
                processed++
                if (response.note.contains("returned")) succeeded++ else failures++
                val space = MAX_ROWS_ACROSS_CATEGORIES - recovered.size
                if (space <= 0) break
                recovered.addAll(response.rows.take(space))
                if (recovered.size >= MAX_ROWS_ACROSS_CATEGORIES ||
                    listOf("HTTP 401", "HTTP 403", "HTTP 429").any { response.note == it }) break
            }
        } finally {
            pool.shutdownNow()
        }
        val unique = recovered.distinctBy {
            listOf(
                it.optString("series_id").ifBlank { it.optString("stream_id") },
                it.optString("name").ifBlank { it.optString("title") },
                it.optString("category_id")
            )
        }
        return FetchOutcome(unique, "${unique.size} returned from $succeeded/$processed category requests" +
            (if (failures > 0) " (failed: $failures)" else "") +
            (if (processed < categories.size) " (preview only; full on-disk import follows)" else ""))
    }

    private fun checkXtreamLogin(config: ProviderConfig) {
        require(Net.validHttp(config.url)) { "Invalid Xtream server address. Check its http:// or https:// prefix." }
        val response = try {
            JSONObject(Net.readText(api(config), 350_000))
        } catch (e: Exception) {
            error("Cannot connect to Xtream API: ${safeFailure(e)}. Check server address, network and provider status.")
        }
        val account = response.optJSONObject("user_info")
            ?: error("Xtream server did not return account information (unsupported response).")
        when (account.optString("auth")) {
            "1", "true" -> Unit
            else -> error("Xtream login rejected. Check the username and password in Settings.")
        }
    }

    /** No URLs, credentials, raw HTTP bodies or provider secrets in user diagnostics. */
    fun importErrorMessage(error: Exception): String {
        val message = error.message.orEmpty()
        return if (message.startsWith("Cannot connect to Xtream API:") ||
            message.startsWith("Xtream server did not return") ||
            message.startsWith("Xtream login rejected.") ||
            message.startsWith("No provider categories received") ||
            message.startsWith("Both Films and Series catalogues are unavailable")) {
            message.take(350)
        } else "Cannot read provider catalogue: ${safeFailure(error)}. Check the connection and retry."
    }

    data class ProviderCategory(val id: String, val name: String, val type: ContentType)

    /** Retrieve ALL category names, not only the first 80 used for preview import. */
    fun providerCategories(provider: ProviderConfig, type: ContentType): List<ProviderCategory> {
        require(type == ContentType.SERIES || type == ContentType.MOVIE)
        val config = when (provider.kind) {
            "xtream" -> provider
            "m3u" -> xtreamFromM3u(provider)
                ?: error("To browse all provider categories, connect with your Xtream login.")
            else -> error("This source does not expose Xtream category APIs.")
        }
        val action = if (type == ContentType.SERIES) "get_series_categories" else "get_vod_categories"
        val response = fetchArray(config, action)
        if (response.rows.isEmpty()) error("No provider categories received (${response.note}).")
        return response.rows.mapNotNull { row ->
            val id = row.optString("category_id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            ProviderCategory(id, row.optString("category_name").ifBlank { "Category $id" }, type)
        }.distinctBy { it.id }
    }

    /**
     * Load one selected language/category at a time, avoiding a 383-category
     * network sweep and the huge memory costs of keeping all VOD in RAM.
     */
    fun loadProviderCategory(provider: ProviderConfig, category: ProviderCategory): List<MediaEntry> {
        val config = when (provider.kind) {
            "xtream" -> provider
            "m3u" -> xtreamFromM3u(provider)
                ?: error("An Xtream login is required for remote categories.")
            else -> error("Remote categories are unavailable for this source.")
        }
        val type = category.type
        require(type == ContentType.SERIES || type == ContentType.MOVIE)
        val action = if (type == ContentType.SERIES) "get_series" else "get_vod_streams"
        val response = fetchArray(config, action, category.id)
        if (response.rows.isEmpty() && response.note != "0 returned")
            error("No titles loaded from ${category.name} (${response.note}).")
        val base = config.url.trimEnd('/')
        val user = Net.urlEncode(config.username)
        val pass = Net.urlEncode(config.password)
        return response.rows.mapNotNull { row ->
            val title = row.optString("name").ifBlank { row.optString("title") }.ifBlank { "Untitled" }
            val categoryKey = row.optString("category_id").ifBlank { category.id }
            if (type == ContentType.SERIES) {
                val sid = row.optString("series_id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                MediaEntry(
                    id = makeId("series", base, sid, row.optString("name"), categoryKey),
                    title = title, type = ContentType.SERIES, group = category.name,
                    image = row.optString("cover").ifBlank { row.optString("stream_icon") },
                    description = row.optString("plot"), seriesId = sid,
                    backdrop = row.optJSONArray("backdrop")?.optString(0) ?: row.optString("backdrop")
                )
            } else {
                val sid = row.optString("stream_id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val ext = row.optString("container_extension", "mp4").let {
                    if (it.matches(Regex("[a-zA-Z0-9]{2,5}"))) it else "mp4"
                }
                MediaEntry(
                    id = makeId("movie", base, sid, row.optString("name"), categoryKey),
                    title = title, url = "$base/movie/$user/$pass/$sid.$ext",
                    type = ContentType.MOVIE, group = category.name,
                    image = row.optString("stream_icon").ifBlank { row.optString("cover") },
                    description = row.optString("plot"), backdrop = row.optString("cover_big")
                )
            }
        }
    }

    data class ImportProgress(
        val done: Int, val total: Int, val category: String,
        val films: Int, val series: Int, val failed: Int
    )

    /**
     * Fetch a bounded number of categories concurrently, while writing their
     * results on ONE coordinator thread. This avoids both 827 serial HTTP
     * round trips and the dangerous memory spikes of unlimited parallelism.
     * Completed category transactions are resumed, not downloaded again.
     */
    fun importAllVod(
        provider: ProviderConfig,
        database: CatalogDatabase,
        onProgress: (ImportProgress) -> Unit,
        isCancelled: () -> Boolean = { false },
        parallelRequests: Int = 3
    ): ImportProgress {
        val config = xtreamFor(provider) ?: error("Xtream credentials required for VOD import")
        onProgress(ImportProgress(0, 0, "Checking Xtream login", 0, 0, 0))
        checkXtreamLogin(config)
        onProgress(ImportProgress(0, 0, "Reading film categories", 0, 0, 0))
        val movieResult = runCatching { providerCategories(config, ContentType.MOVIE) }
        onProgress(ImportProgress(0, 0, "Reading series categories", 0, 0, 0))
        val seriesResult = runCatching { providerCategories(config, ContentType.SERIES) }
        if (movieResult.isFailure && seriesResult.isFailure) {
            val movieCause = movieResult.exceptionOrNull() as? Exception ?: Exception()
            val seriesCause = seriesResult.exceptionOrNull() as? Exception ?: Exception()
            error("Both Films and Series catalogues are unavailable. Films: " +
                "${importErrorMessage(movieCause)}. Series: ${importErrorMessage(seriesCause)}.")
        }
        val movies = movieResult.getOrDefault(emptyList())
        val series = seriesResult.getOrDefault(emptyList())
        // Mix films and series so both libraries become usable early.
        val all = ArrayList<ProviderCategory>(movies.size + series.size)
        repeat(maxOf(movies.size, series.size)) { i ->
            if (i < series.size) all.add(series[i])
            if (i < movies.size) all.add(movies[i])
        }
        var completed = 0
        val pending = ArrayList<ProviderCategory>()
        all.forEach {
            if (database.isComplete(it.type, it.id)) completed++
            else pending.add(it)
        }
        var failures = (if (movieResult.isFailure) 1 else 0) +
            (if (seriesResult.isFailure) 1 else 0)
        var films = database.count(ContentType.MOVIE)
        var shows = database.count(ContentType.SERIES)
        onProgress(ImportProgress(completed, all.size,
            if (failures > 0) "One media type is unavailable; importing the other" else "Importing all categories",
            films, shows, failures))
        if (pending.isEmpty()) {
            return ImportProgress(completed, all.size, "Already up to date", films, shows, 0)
        }

        val pool = Executors.newFixedThreadPool(parallelRequests.coerceIn(1, 4))
        val completionQueue =
            ExecutorCompletionService<Pair<ProviderCategory, Result<List<MediaEntry>>>>(pool)
        var queued = 0
        var running = 0
        try {
            while ((queued < pending.size || running > 0) &&
                !isCancelled() && !Thread.currentThread().isInterrupted) {
                while (running < parallelRequests.coerceIn(1, 4) && queued < pending.size) {
                    val category = pending[queued++]
                    completionQueue.submit(Callable {
                        category to runCatching { loadProviderCategory(config, category) }
                    })
                    running++
                }
                val finished = completionQueue.poll(10, TimeUnit.SECONDS)
                if (finished == null) {
                    onProgress(ImportProgress(completed, all.size,
                        "Waiting for provider response (downloads still active)",
                        films, shows, failures))
                    continue
                }
                val (category, fetched) = finished.get()
                running--
                fetched.fold(
                    onSuccess = { items ->
                        // SQLite has a single writer; no concurrent write contention.
                        database.storeCategory(category, items)
                        completed++
                        if (category.type == ContentType.MOVIE) films += items.distinctBy { it.id }.size
                        else shows += items.distinctBy { it.id }.size
                    },
                    onFailure = { error ->
                        failures++
                        Log.w("LumaTV", "Category import failed: ${error.javaClass.simpleName}")
                    }
                )
                onProgress(ImportProgress(completed, all.size, category.name, films, shows, failures))
            }
        } finally {
            pool.shutdownNow()
        }
        val result = ImportProgress(completed, all.size,
            if (completed == all.size) "All categories downloaded"
            else "Unfinished categories will be retried",
            films, shows, failures)
        onProgress(result)
        return result
    }

    /**
     * Called only when the user opens Live TV. Avoid the huge global streams
     * request and don't block browsing Film/Series catalogues on Live TV.
     */
    fun loadXtreamLive(config: ProviderConfig, onStage: (String) -> Unit = {}): CatalogLoad =
        loadXtream(config, loadLive = true, includeVod = false,
            liveBootstrapLimit = 3, skipGlobalLive = true, onStage = onStage)

    private fun loadXtream(
        config: ProviderConfig, loadLive: Boolean = true, includeVod: Boolean = true,
        liveBootstrapLimit: Int = 80, skipGlobalLive: Boolean = false,
        onStage: (String) -> Unit = {}
    ): CatalogLoad {
        require(Net.validHttp(config.url)) { "Enter a full http:// or https:// server address." }
        onStage("Authenticating with Xtream provider")
        val status = JSONObject(Net.readText(api(config), 1_000_000))
        val auth = status.optJSONObject("user_info") ?: error("The server did not return valid account information.")
        if (auth.optString("auth") != "1") error("The provider rejected the login details.")
        if (loadLive) onStage("Reading Live TV category names")
        val liveCategories = if (loadLive) fetchArray(config, "get_live_categories") else FetchOutcome(emptyList(), "skipped")
        val movieCategories = if (includeVod) fetchArray(config, "get_vod_categories") else FetchOutcome(emptyList(), "deferred")
        val seriesCategories = if (includeVod) fetchArray(config, "get_series_categories") else FetchOutcome(emptyList(), "deferred")
        val liveGroups = liveCategories.rows.associate {
            it.optString("category_id") to it.optString("category_name", "Live TV")
        }
        val movieGroups = movieCategories.rows.associate {
            it.optString("category_id") to it.optString("category_name", "Films")
        }
        val seriesGroups = seriesCategories.rows.associate {
            it.optString("category_id") to it.optString("category_name", "Series")
        }
        // Fetch and convert each catalogue in sequence. Holding several raw
        // JSONObject arrays simultaneously could exhaust the Fire TV heap.
        val base = config.url.trimEnd('/')
        val u = Net.urlEncode(config.username)
        val p = Net.urlEncode(config.password)
        if (loadLive) onStage("Loading a small Live TV preview")
        val liveResponse = if (loadLive) fetchCatalogue(
            config, "get_live_streams", liveCategories.rows, liveBootstrapLimit,
            skipGlobal = skipGlobalLive
        ) else FetchOutcome(emptyList(), "skipped")
        val live = liveResponse.rows.mapNotNull { j ->
            val sid = j.optString("stream_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val extension = if (config.liveFormat == "ts") "ts" else "m3u8"
            MediaEntry(
                id = makeId("live", base, sid), title = j.optString("name", "Channel"),
                url = "$base/live/$u/$p/$sid.$extension", type = ContentType.LIVE,
                group = liveGroups[j.optString("category_id")] ?: "Live TV",
                image = j.optString("stream_icon"), epgId = j.optString("epg_channel_id")
            )
        }
        val liveNote = liveResponse.note
        val movieResponse = if (includeVod) fetchCatalogue(config, "get_vod_streams", movieCategories.rows)
            else FetchOutcome(emptyList(), "downloading by category")
        val vod = movieResponse.rows.mapNotNull { j ->
            val sid = j.optString("stream_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ext = j.optString("container_extension", "mp4").let {
                if (it.matches(Regex("[a-zA-Z0-9]{2,5}"))) it else "mp4"
            }
            MediaEntry(
                id = makeId("movie", base, sid, j.optString("name"), j.optString("category_id")),
                title = j.optString("name", "Film"),
                url = "$base/movie/$u/$p/$sid.$ext", type = ContentType.MOVIE,
                group = movieGroups[j.optString("category_id")] ?: "Films",
                image = j.optString("stream_icon").ifBlank { j.optString("cover") },
                description = j.optString("plot"), backdrop = j.optString("cover_big")
            )
        }
        val movieNote = movieResponse.note
        val seriesResponse = if (includeVod) fetchCatalogue(config, "get_series", seriesCategories.rows)
            else FetchOutcome(emptyList(), "downloading by category")
        val shows = seriesResponse.rows.mapNotNull { j ->
            val sid = j.optString("series_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MediaEntry(
                id = makeId("series", base, sid, j.optString("name"), j.optString("category_id")),
                title = j.optString("name", "Show"),
                type = ContentType.SERIES, group = seriesGroups[j.optString("category_id")] ?: "Series",
                image = j.optString("cover").ifBlank { j.optString("stream_icon") },
                description = j.optString("plot"), seriesId = sid,
                backdrop = j.optJSONArray("backdrop")?.optString(0) ?: j.optString("backdrop")
            )
        }
        val seriesNote = seriesResponse.note
        val all = live + vod + shows
        // A provider may expose VOD/series while its global Live API is oversized.
        // Never reject valid credentials simply because the startup live preview
        // found no channels; the complete VOD import follows independently.
        if (all.isEmpty() && loadLive && includeVod) {
            error("The provider returned no playable media. Check its Live / Films / Series APIs.")
        }
        return CatalogLoad(all, "Xtream catalogue\n" +
            "Live TV: ${live.size} (${liveNote})\n" +
            "Films: ${vod.size} (${movieNote})\n" +
            "Series: ${shows.size} (${seriesNote})\n\n" +
            "Movie categories: ${movieCategories.rows.size} (${movieCategories.note})\n" +
            "Series categories: ${seriesCategories.rows.size} (${seriesCategories.note})\n" +
            "If your provider has films/series but these counts are zero, check whether its Xtream login enables VOD and Series APIs.")
    }

    fun episodes(config: ProviderConfig, show: MediaEntry): List<MediaEntry> {
        // Shows automatically discovered through an M3U login use Xtream episode APIs too.
        val source = if (config.kind == "xtream") config else {
            val derived = xtreamFromM3u(config)
            // Xtream-discovered series have a provider series ID, while
            // locally grouped M3U series have matching playlist episode rows.
            if (derived != null && show.seriesId.isNotBlank() &&
                AppData.entries.none { it.type == ContentType.EPISODE && it.seriesId == show.seriesId }
            ) derived else null
        }
        if (source == null) {
            return AppData.entries.filter { it.type == ContentType.EPISODE && it.seriesId == show.seriesId }
                .sortedWith(compareBy({ it.season }, { it.episode }, { it.title }))
        }
        val obj = JSONObject(Net.readText(api(source, "get_series_info", show.seriesId), 20_000_000))
        val seasons = obj.optJSONObject("episodes") ?: error("No episodes were returned for this series.")
        val result = mutableListOf<MediaEntry>()
        val base = source.url.trimEnd('/')
        val u = Net.urlEncode(source.username)
        val p = Net.urlEncode(source.password)
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
            "Animated short film used for playback testing"),
        // Small test series using publicly published example media to verify autoplay.
        MediaEntry("demo_series", "Demo Series", type = ContentType.SERIES,
            group = "Sample series", seriesId = "demo_series"),
        MediaEntry("demo_episode_1", "Demo Episode 1", "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            ContentType.EPISODE, "Demo Series", seriesId = "demo_series", season = 1, episode = 1),
        MediaEntry("demo_episode_2", "Demo Episode 2", "https://storage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
            ContentType.EPISODE, "Demo Series", seriesId = "demo_series", season = 1, episode = 2)
    )
}
