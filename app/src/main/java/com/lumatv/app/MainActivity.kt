package com.lumatv.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var storage: Storage
    private val catalogue by lazy { CatalogDatabase(this) }
    private var browseOffset = 0
    private val browsePageSize = 180
    private var browsingTotal = 0
    private var loadingMessage = "Connecting to your library"
    private var loadingDone = 0
    private var loadingTotal = 0
    private var loadingFilms = 0
    private var loadingShows = 0
    private var loadingFailures = 0
    private var progressEvents = 0
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var searchBox: EditText
    private lateinit var footer: LinearLayout
    private val tvNavigation = linkedMapOf<String, TextView>()
    private var progressBarView: ProgressBar? = null
    private var progressHeadline: TextView? = null
    private var progressCounter: TextView? = null
    private var progressSubtitle: TextView? = null
    private var syncingLabel: TextView? = null
    private var importing = false
    @Volatile private var cancelImportRequested = false
    private val searchRender = Runnable { if (::body.isInitialized && !isFinishing) render() }
    private val worker = Executors.newSingleThreadExecutor()
    private val liveWorker = Executors.newSingleThreadExecutor()
    private var liveLoading = false
    private var liveFailure = ""
    private var initialTitlesRendered = false
    private var liveMessage = "Loading Live TV channels"
    private val main = Handler(Looper.getMainLooper())
    private var tab = "Home"
    private var category = "All"
    private var search = ""
    private val isTv by lazy {
        packageManager.hasSystemFeature("android.software.leanback") ||
            (getSystemService(UI_MODE_SERVICE) as? android.app.UiModeManager)
                ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
    private var predictiveBackCallback: android.window.OnBackInvokedCallback? = null
    private var loading = false
    private var errorText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install(applicationContext)
        storage = Storage(this)
        AppData.config = storage.loadProvider()
        // A large encrypted library must never be decoded on Android's UI thread.
        AppData.entries = emptyList()
        AppData.catalogReport = storage.loadCatalogReport()
        buildShell()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            predictiveBackCallback = android.window.OnBackInvokedCallback {
                if (!consumeBackNavigation()) finish()
            }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                predictiveBackCallback!!
            )
        }
        if (isTv) tvNavigation["Home"]?.requestFocus()
        val provider = AppData.config
        if (provider == null) {
            render()
        } else if (storage.needsCatalogRefresh()) {
            // On upgrade, skip decoding a potentially huge legacy cache.
            refresh()
        } else {
            loading = true
            render()
            worker.execute {
                val cached = storage.loadCatalog()
                val hasSavedVod = catalogue.count(ContentType.MOVIE) > 0 ||
                    catalogue.count(ContentType.SERIES) > 0
                main.post {
                    if (isFinishing || isDestroyed || AppData.config != provider) return@post
                    loading = false
                    if (cached.isEmpty() && !hasSavedVod) {
                        refresh()
                    } else {
                        AppData.entries = cached
                        render()
                        if (SourceService.xtreamFor(provider) != null &&
                            !storage.fullImportComplete()) resumeFullImport(provider)
                        if (SourceService.epgUrl(provider).isNotBlank()) fetchEpg(provider, cached)
                    }
                }
            }
        }
    }

    private var hasResumedOnce = false

    override fun onResume() {
        super.onResume()
        // Avoid destroying the Fire TV grid and losing D-pad position each
        // time playback or another activity returns to the foreground.
        if (hasResumedOnce && ::body.isInitialized && !loading &&
            (!isTv || tab == "My List")) render()
        hasResumedOnce = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildShell()
        render()
    }

    override fun onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            predictiveBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            predictiveBackCallback = null
        }
        worker.shutdownNow()
        liveWorker.shutdownNow()
        main.removeCallbacks(searchRender)
        super.onDestroy()
    }

    private fun buildShell() {
        root = Ui.vertical(this).apply {
            setBackgroundColor(Ui.BG)
            isFocusableInTouchMode = true // Do not summon the virtual keyboard on TV startup.
        }
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                root.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
            root.requestApplyInsets()
        }
        val header = Ui.horizontal(this).apply {
            setPadding(dp(20), dp(16), dp(20), dp(11))
        }
        val brand = Ui.vertical(this)
        brand.addView(Ui.text(this, "LUMA", 29f, Ui.WHITE, true).apply { letterSpacing = 0.16f })
        brand.addView(Ui.text(this, "TV  •  STREAM BEAUTIFULLY", 9.5f, Ui.MINT, true).apply { letterSpacing = .16f })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Ui.button(this, "⚙", false) { showSettings() }.apply {
            textSize = 21f; setPadding(dp(15), dp(7), dp(15), dp(7))
        })
        run {
            syncingLabel = Ui.text(this, "", 13f, Ui.MINT, true).apply {
                isFocusable = true
                isClickable = true
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    if (importing) {
                        loading = true
                        render()
                    }
                }
            }
            header.addView(syncingLabel)
        }
        root.addView(header)
        tvNavigation.clear()
        if (isTv) {
            val nav = Ui.horizontal(this).apply {
                setPadding(dp(24), dp(4), dp(24), dp(12))
            }
            val labels = listOf("Home", "Live TV", "Films", "Series", "My List")
            labels.forEach { name ->
                val button = Ui.text(this, name, 17f, Ui.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(25), dp(13), dp(25), dp(13))
                    isFocusable = true; isClickable = true
                    setOnClickListener { selectTab(name) }
                    setOnFocusChangeListener { _, _ -> updateTvNavigation() }
                }
                tvNavigation[name] = button
                nav.addView(button, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                    leftMargin = dp(5); rightMargin = dp(5)
                })
            }
            root.addView(nav)
        }

        searchBox = EditText(this).apply {
            hint = searchHint()
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.WHITE)
            textSize = if (isTv) 17f else 14f
            isSingleLine = true
            background = Ui.bg(Ui.SURFACE, dp(15).toFloat())
            setPadding(dp(17), dp(12), dp(12), dp(12))
            setText(search)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    search = s?.toString().orEmpty()
                    browseOffset = 0
                    main.removeCallbacks(searchRender)
                    main.postDelayed(searchRender, if (isTv) 280 else 220)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        val searchRow = Ui.horizontal(this).apply {
            setPadding(dp(if (isTv) 36 else 16), 0, dp(if (isTv) 36 else 16), dp(11))
        }
        searchRow.addView(searchBox, LinearLayout.LayoutParams(0, dp(if (isTv) 55 else 47), 1f))
        searchRow.addView(Ui.button(this, "✕", false) {
            clearSearch()
        }, LinearLayout.LayoutParams(-2, dp(if (isTv) 55 else 47)).apply { leftMargin = dp(8) })
        root.addView(searchRow)
        body = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = Ui.horizontal(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(10))
            setBackgroundColor(0xFF10131E.toInt())
        }
        if (!isTv) root.addView(footer)
    }

    private fun selectTab(name: String) {
        tab = name
        category = "All"
        browseOffset = 0
        clearSearch(false)
        searchBox.hint = searchHint()
        render()
        if (name == "Live TV") requestLiveChannels()
    }

    /**
     * Live TV is completely independent from Films/Series catalogue imports.
     * If the provider's Live endpoint is slow or huge, the UI remains usable.
     */
    private fun requestLiveChannels(force: Boolean = false) {
        val config = AppData.config ?: return
        if (config.kind != "xtream" || liveLoading ||
            (!force && AppData.entries.any { it.type == ContentType.LIVE })) return
        liveLoading = true
        liveFailure = ""
        liveMessage = "Reading Live TV category names"
        if (tab == "Live TV") render()
        liveWorker.execute {
            try {
                val result = SourceService.loadXtreamLive(config) { stage ->
                    main.post {
                        if (!isFinishing && !isDestroyed && AppData.config == config &&
                            tab == "Live TV") {
                            liveMessage = stage
                            render()
                        }
                    }
                }
                if (AppData.config != config || cancelImportRequested) return@execute
                val merged = AppData.entries.filter { it.type != ContentType.LIVE } +
                    result.entries.filter { it.type == ContentType.LIVE }
                storage.saveCatalog(merged)
                main.post {
                    if (isFinishing || isDestroyed || AppData.config != config) return@post
                    AppData.entries = merged
                    liveLoading = false
                    liveFailure = if (result.entries.none { it.type == ContentType.LIVE })
                        "No channels returned by the provider's first three Live TV categories." else ""
                    if (tab == "Live TV" || tab == "Home") render()
                }
            } catch (_: Exception) {
                main.post {
                    if (isFinishing || isDestroyed || AppData.config != config) return@post
                    liveLoading = false
                    liveFailure = "The Live TV server request failed. You can retry without losing your Films or Series."
                    if (tab == "Live TV") render()
                }
            }
        }
    }

    private fun updateTvNavigation() {
        tvNavigation.forEach { (name, button) ->
            val focused = button.isFocused
            val selected = name == tab
            button.background = Ui.bg(
                if (focused) Ui.MINT else if (selected) Ui.PANEL else Ui.BG,
                dp(12).toFloat(), if (focused) Ui.WHITE else null
            )
            button.setTextColor(if (focused) Ui.BG else if (selected) Ui.MINT else Ui.WHITE)
        }
    }

    private fun updateProgressViews() {
        progressHeadline?.text = loadingMessage
        progressBarView?.apply {
            isIndeterminate = loadingTotal <= 0
            max = loadingTotal.coerceAtLeast(1)
            progress = (loadingDone + loadingFailures).coerceAtMost(max)
        }
        progressCounter?.text = if (loadingTotal > 0)
            "${(loadingDone + loadingFailures).coerceAtMost(loadingTotal)} of $loadingTotal categories processed"
        else "Connecting…"
        progressSubtitle?.text =
            "Films: $loadingFilms    Series: $loadingShows    Failed: $loadingFailures"
        syncingLabel?.apply {
            text = if (importing && loadingTotal > 0)
                "Syncing ${(loadingDone + loadingFailures).coerceAtMost(loadingTotal)}/$loadingTotal"
            else if (importing) "Syncing catalogue…" else ""
            visibility = if (importing) View.VISIBLE else View.GONE
        }
    }

    private fun render() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        if (isTv) {
            updateTvNavigation()
        } else {
            footer.removeAllViews()
            val tabs = listOf("Home" to "⌂", "Live TV" to "▣", "Films" to "▶", "Series" to "▤", "My List" to "♥")
            tabs.forEach { (name, symbol) ->
                val selected = tab == name
                val column = Ui.vertical(this).apply {
                    gravity = Gravity.CENTER
                    setPadding(1, dp(2), 1, dp(2))
                    isClickable = true; isFocusable = true
                    background = Ui.bg(if (selected) Ui.PANEL else 0xFF10131E.toInt(), dp(9).toFloat())
                    Ui.tvFocus(this)
                    setOnClickListener { selectTab(name) }
                }
                column.addView(Ui.text(this, symbol, 19f, if (selected) Ui.MINT else Ui.MUTED, selected).apply {
                    gravity = Gravity.CENTER
                })
                column.addView(Ui.text(this, name, 10f, if (selected) Ui.WHITE else Ui.MUTED, selected).apply {
                    gravity = Gravity.CENTER
                })
                footer.addView(column, LinearLayout.LayoutParams(0, dp(54), 1f))
            }
        }
        if (loading) { showLoading(); return }
        if (AppData.config == null) { showWelcome(); return }
        val visible = storage.visibleEntries(AppData.entries).filter { it.type != ContentType.EPISODE }
        val vodType = if (SourceService.xtreamFor(AppData.config ?: ProviderConfig("", "", "")) != null)
            when (tab) {
                "Films" -> ContentType.MOVIE
                "Series" -> ContentType.SERIES
                else -> null
            } else null
        val scoped = when (tab) {
            "Live TV" -> visible.filter { it.type == ContentType.LIVE }
            "Films" -> visible.filter { it.type == ContentType.MOVIE }
            "Series" -> visible.filter { it.type == ContentType.SERIES }
            "My List" -> storage.favorites(visible)
            else -> visible
        }
        val matches = if (vodType != null && AppData.config != null) {
            val total = catalogue.count(vodType, search, category)
            browsingTotal = total
            if (browseOffset >= total && browseOffset > 0) browseOffset = 0
            val provider = SourceService.xtreamFor(AppData.config!!)!!
            catalogue.page(provider, vodType, search, category, browsePageSize, browseOffset)
        } else {
            browsingTotal = 0
            if (search.isBlank()) scoped else scoped.filter {
                it.title.contains(search, ignoreCase = true) || it.group.contains(search, ignoreCase = true)
            }
        }
        when {
            // Having no titles is normal while the account is being checked
            // and categories are being downloaded. This is NOT a login failure.
            importing && AppData.entries.isEmpty() && browsingTotal == 0 &&
                catalogue.count(ContentType.MOVIE) == 0 && catalogue.count(ContentType.SERIES) == 0 ->
                showLoading()
            tab == "Live TV" && matches.isEmpty() && liveLoading -> showBrowse(matches, "Live TV")
            AppData.entries.isEmpty() && browsingTotal == 0 &&
                catalogue.count(ContentType.MOVIE) == 0 && catalogue.count(ContentType.SERIES) == 0 &&
                !liveLoading -> showError()
            search.isNotBlank() -> showBrowse(matches, "Search ${tab.lowercase()}")
            tab == "Home" -> showHome(visible)
            else -> showBrowse(matches, tab)
        }
    }

    private fun searchHint(): String = when (tab) {
        "Live TV" -> "⌕   Search live channels"
        "Films" -> "⌕   Search films only"
        "Series" -> "⌕   Search series only"
        "My List" -> "⌕   Search My List"
        else -> "⌕   Search all titles"
    }

    private fun clearSearch(redraw: Boolean = true) {
        search = ""
        if (::searchBox.isInitialized) {
            if (searchBox.text.isNotEmpty()) searchBox.setText("")
            searchBox.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(searchBox.windowToken, 0)
        }
        main.removeCallbacks(searchRender)
        if (redraw && ::body.isInitialized) render()
    }

    private fun consumeBackNavigation(): Boolean {
        when {
            search.isNotBlank() -> clearSearch()
            category != "All" -> { category = "All"; render() }
            tab != "Home" -> { selectTab("Home"); tvNavigation["Home"]?.requestFocus() }
            else -> return false
        }
        return true
    }

    @Deprecated("Legacy back key support for Android and Fire OS")
    override fun onBackPressed() {
        if (!consumeBackNavigation()) super.onBackPressed()
    }

    private fun showLoading() {
        val shell = Ui.vertical(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(if (isTv) 75 else 22), dp(18), dp(if (isTv) 75 else 22), dp(18))
        }
        shell.addView(Ui.text(this, "BUILDING YOUR LIBRARY",
            if (isTv) 29f else 24f, Ui.WHITE, true).apply {
            gravity = Gravity.CENTER
        }, Ui.columnParams(this, if (isTv) 20 else 16))
        progressHeadline = Ui.text(this, loadingMessage,
            if (isTv) 19f else 15f, Ui.MINT, true).apply {
            gravity = Gravity.CENTER; maxLines = 2
        }
        shell.addView(progressHeadline, Ui.columnParams(this, 12))
        progressBarView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        shell.addView(progressBarView, LinearLayout.LayoutParams(-1, dp(16)))
        progressCounter = Ui.text(this, "", if (isTv) 19f else 16f, Ui.WHITE, true).apply {
            gravity = Gravity.CENTER
        }
        shell.addView(progressCounter, Ui.columnParams(this, 12))
        progressSubtitle = Ui.text(this, "", if (isTv) 15f else 13f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
        }
        shell.addView(progressSubtitle, Ui.columnParams(this, 12))
        shell.addView(Ui.text(this, "Downloading in parallel • saved by category • safe to resume",
            12f, Ui.MUTED).apply { gravity = Gravity.CENTER }, Ui.columnParams(this, 14))
        shell.addView(Ui.button(this, "Browse what's ready  ▶", false) {
            loading = false
            progressBarView = null; progressHeadline = null
            progressCounter = null; progressSubtitle = null
            selectTab(if (loadingShows > 0) "Series" else if (loadingFilms > 0) "Films" else "Live TV")
        }, Ui.columnParams(this, 8))
        shell.addView(Ui.button(this, "Cancel connection / import", false) {
            cancelImportRequested = true
            importing = false
            loading = false
            progressBarView = null; progressHeadline = null
            progressCounter = null; progressSubtitle = null
            loadingMessage = "Import paused"
            errorText = "The import was stopped. Your saved library is still available. Use Settings → Refresh library to retry."
            render()
        }, Ui.columnParams(this, 10))
        body.addView(shell, FrameLayout.LayoutParams(-1, -1))
        updateProgressViews()
    }

    private fun showWelcome() {
        val scroll = ScrollView(this).apply { setFillViewport(true) }
        val column = Ui.vertical(this).apply { setPadding(dp(24), dp(35), dp(24), dp(24)) }
        column.addView(Ui.text(this, "YOUR SCREEN.\nYOUR WORLD.", 35f, Ui.WHITE, true).apply { letterSpacing = .04f })
        column.addView(Ui.text(this, "A beautiful home for your live television, films and series.", 16f, Ui.MUTED).apply {
            setPadding(0, dp(13), 0, dp(26))
        })
        val preview = FrameLayout(this).apply {
            background = Ui.gradient(dp(20).toFloat())
            clipToOutline = true
        }
        preview.addView(Ui.text(this, "▶", 66f, Ui.MINT, true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        column.addView(preview, LinearLayout.LayoutParams(-1, dp(170)))
        column.addView(Ui.spacer(this, 27))
        column.addView(Ui.button(this, "Connect Xtream provider") { connectDialog("xtream") }, Ui.columnParams(this, 10))
        column.addView(Ui.button(this, "Import M3U playlist", false) { connectDialog("m3u") }, Ui.columnParams(this, 10))
        column.addView(Ui.button(this, "Open local M3U file", false) { pickFile() }, Ui.columnParams(this, 10))
        column.addView(Ui.button(this, "Explore with sample streams", false) {
            connect(ProviderConfig("demo", "Demo library", "https://example.com"))
        }, Ui.columnParams(this, 16))
        column.addView(Ui.text(this, "LumaTV does not include IPTV subscriptions or copyrighted channels. Add a source you are authorised to watch.", 12f, Ui.MUTED))
        scroll.addView(column)
        body.addView(scroll)
    }

    private fun showError() {
        val scroll = ScrollView(this)
        val column = Ui.vertical(this).apply { setPadding(dp(24), dp(48), dp(24), dp(20)) }
        column.addView(Ui.text(this, "Couldn't load your library", 25f, Ui.WHITE, true), Ui.columnParams(this, 12))
        column.addView(Ui.text(this, errorText.ifBlank { "The playlist may be unavailable. Check your connection and provider settings." }, 14f, Ui.MUTED), Ui.columnParams(this, 20))
        column.addView(Ui.button(this, "Try again") { refresh() }, Ui.columnParams(this, 12))
        column.addView(Ui.button(this, "Edit source details", false) {
            val kind = AppData.config?.kind ?: "xtream"
            connectDialog(if (kind == "m3u") "m3u" else "xtream")
        })
        scroll.addView(column); body.addView(scroll)
    }

    private fun showHome(list: List<MediaEntry>) {
        if (list.isEmpty() && catalogue.count(ContentType.MOVIE) == 0 &&
            catalogue.count(ContentType.SERIES) == 0) { showBrowse(list, "Your library"); return }
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val column = Ui.vertical(this)
        val previewConfig = AppData.config?.let { SourceService.xtreamFor(it) }
        val hero = list.firstOrNull { it.type == ContentType.MOVIE && it.image.isNotBlank() }
            ?: list.firstOrNull { it.type == ContentType.SERIES && it.image.isNotBlank() }
            ?: previewConfig?.let { catalogue.page(it, ContentType.MOVIE, limit = 1).firstOrNull() }
            ?: previewConfig?.let { catalogue.page(it, ContentType.SERIES, limit = 1).firstOrNull() }
            ?: list.firstOrNull()
        if (hero == null) return
        column.addView(makeHero(hero), Ui.columnParams(this, 18))
        val recents = storage.recents(list)
        if (recents.isNotEmpty()) shelf(column, "Continue watching", recents)
        shelf(column, "Live right now", list.filter { it.type == ContentType.LIVE }.take(25))
        val xtream = AppData.config?.let { SourceService.xtreamFor(it) }
        val films = if (xtream != null) catalogue.page(xtream, ContentType.MOVIE, limit = 25)
            else list.filter { it.type == ContentType.MOVIE }.take(25)
        shelf(column, "Films for you", films)
        val shows = if (xtream != null) catalogue.page(xtream, ContentType.SERIES, limit = 25)
            else list.filter { it.type == ContentType.SERIES }
        shelf(column, "Series to discover", shows.take(25))
        if (shows.size > 25) {
            column.addView(Ui.button(this, "Browse all ${shows.size} series and language editions", false) {
                tab = "Series"; category = "All"; clearSearch()
                searchBox.hint = searchHint()
                render()
            }, Ui.columnParams(this, 14).apply { leftMargin = dp(17); rightMargin = dp(17) })
        }
        val interesting = list.groupBy { it.type to it.group }
            .filter { (k, v) -> v.size >= 4 && k.second.isNotBlank() }
            .entries.sortedByDescending { it.value.size }.take(7)
        interesting.forEach { (key, values) -> shelf(column, key.second, values.take(25)) }
        column.addView(Ui.text(this, "POWERED BY YOUR PLAYLIST  ·  LUMATV", 10f, Ui.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(38))
        })
        scroll.addView(column)
        body.addView(scroll)
    }

    private fun makeHero(entry: MediaEntry): View {
        val height = if (resources.configuration.screenWidthDp >= 600) 320 else 255
        val frame = FrameLayout(this).apply {
            background = Ui.gradient(dp(20).toFloat())
            clipToOutline = true
        }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .47f }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        ImageLoader.into(image, entry.backdrop.ifBlank { entry.image })
        val overlay = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE6080A10.toInt(), 0x77080A10, 0x00080A10))
        }
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val caption = Ui.vertical(this).apply { setPadding(dp(20), dp(12), dp(20), dp(20)) }
        caption.addView(Ui.text(this, "✦  FEATURED ON LUMA", 11f, Ui.MINT, true), Ui.columnParams(this, 8))
        caption.addView(Ui.text(this, entry.title, 29f, Ui.WHITE, true).apply { maxLines = 2 }, Ui.columnParams(this, 6))
        caption.addView(Ui.text(this, "${entry.type.name.lowercase().replaceFirstChar { it.uppercase() }}  •  ${entry.group}", 12f, Ui.WHITE), Ui.columnParams(this, 13))
        val buttons = Ui.horizontal(this)
        buttons.addView(Ui.button(this, if (entry.type == ContentType.SERIES) "View episodes" else "▶  Play now") { open(entry) })
        buttons.addView(Ui.button(this, "ⓘ  Details", false) { showDetails(entry) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        caption.addView(buttons)
        frame.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return FrameLayout(this).apply {
            setPadding(dp(16), 0, dp(16), 0)
            addView(frame, FrameLayout.LayoutParams(-1, dp(height)))
        }
    }

    private fun shelf(parent: LinearLayout, title: String, data: List<MediaEntry>) {
        if (data.isEmpty()) return
        parent.addView(Ui.text(this, title, 19f, Ui.WHITE, true).apply {
            setPadding(dp(17), dp(4), dp(16), dp(12))
        })
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            adapter = MediaAdapter(data, horizontal = true)
            clipToPadding = false
            setPadding(dp(12), 0, dp(12), dp(6))
            isNestedScrollingEnabled = false
        }
        rv.setItemViewCacheSize(if (isTv) 12 else 6)
        parent.addView(rv, LinearLayout.LayoutParams(-1, dp(if (isTv) 285 else 239)).apply {
            bottomMargin = dp(11)
        })
    }

    private fun showBrowse(data: List<MediaEntry>, heading: String) {
        val layout = Ui.vertical(this)
        val headingRow = Ui.horizontal(this).apply { setPadding(dp(16), dp(13), dp(16), dp(14)) }
        headingRow.addView(Ui.text(this, heading, 24f, Ui.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        headingRow.addView(Ui.text(this,
            if (browsingTotal > 0) "${browsingTotal} titles" else "${data.size} titles",
            12f, Ui.MUTED))
        layout.addView(headingRow)
        if ((tab == "Films" || tab == "Series") &&
            AppData.config?.kind in setOf("xtream", "m3u")) {
            val kind = if (tab == "Films") ContentType.MOVIE else ContentType.SERIES
            layout.addView(Ui.button(this,
                "Browse ALL provider categories • languages & subtitles", false
            ) { browseProviderCategories(kind) }, Ui.columnParams(this, 10).apply {
                leftMargin = dp(16); rightMargin = dp(16)
            })
        }
        val groups = listOf("All") + (
            listOfNotNull(category.takeIf { it != "All" }) +
            if (tab == "Films" || tab == "Series") catalogue.categoryNames(
                if (tab == "Films") ContentType.MOVIE else ContentType.SERIES, 79
            ) else data.map { it.group }.distinct().sorted().take(79)
        ).distinct()
        if (groups.size > 1 && search.isBlank()) {
            val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val chips = Ui.horizontal(this).apply { setPadding(dp(13), 0, dp(13), dp(13)) }
            groups.forEach { name ->
                val chip = Ui.text(this, name, 12f, if (category == name) Ui.BG else Ui.WHITE, category == name).apply {
                    gravity = Gravity.CENTER
                    background = Ui.bg(if (category == name) Ui.MINT else Ui.PANEL, dp(20).toFloat())
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                    isClickable = true; isFocusable = true
                    if (isTv) Ui.tvFocus(this)
                    setOnClickListener { category = name; browseOffset = 0; render() }
                }
                chips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            }
            chipScroll.addView(chips); layout.addView(chipScroll)
        }
        val filtered = if (browsingTotal > 0) data
            else if (category == "All" || search.isNotBlank()) data
            else data.filter { it.group == category }
        if (filtered.isEmpty()) {
            if (tab == "Live TV" && !liveLoading && AppData.config?.kind == "xtream") {
                layout.addView(Ui.button(this, "Load / retry Live TV channels", false) {
                    requestLiveChannels(force = true)
                }, Ui.columnParams(this, 10).apply {
                    leftMargin = dp(18); rightMargin = dp(18)
                })
            }
            val message = when (tab) {
                "Films" -> if (importing)
                    "Importing films in the background. Categories and film titles will appear as they download."
                else "No films available. Check catalogue diagnostics or refresh the source."
                "Series" -> if (importing)
                    "Importing series in the background. Versions and subtitle editions will appear as categories finish."
                else "No series available. Check catalogue diagnostics or refresh the source."
                "Live TV" -> when {
                    liveLoading -> liveMessage + " — Films and Series can still be used."
                    liveFailure.isNotBlank() -> liveFailure
                    else -> "No live channels loaded. Try loading channels or check your channel filters."
                }
                else -> if (importing)
                    "Importing all Films and Series in the background. Select Films or Series to browse available titles."
                else "Nothing here yet. Try another category or add a playlist."
            }
            layout.addView(Ui.text(this, message, 15f, Ui.MUTED).apply {
                setPadding(dp(23), dp(35), dp(23), 0)
            })
            if ((tab == "Films" || tab == "Series") && search.isBlank()) {
                layout.addView(Ui.button(this, "View catalogue diagnostics", false) {
                    AlertDialog.Builder(this)
                        .setTitle("Catalogue import report")
                        .setMessage(AppData.catalogReport.ifBlank { "No diagnostics yet. Refresh library in Settings." })
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Refresh now") { _, _ -> refresh() }
                        .show()
                }, Ui.columnParams(this, 12).apply {
                    leftMargin = dp(21); rightMargin = dp(21)
                })
            }
        } else {
            val span = when {
                isTv && resources.configuration.screenWidthDp >= 1050 -> 6
                isTv -> 5
                resources.configuration.screenWidthDp >= 840 -> 5
                resources.configuration.screenWidthDp >= 600 -> 4
                resources.configuration.screenWidthDp >= 385 -> 3
                else -> 2
            }
            layout.addView(RecyclerView(this).apply {
                layoutManager = GridLayoutManager(this@MainActivity, span)
                adapter = MediaAdapter(filtered, horizontal = false)
                setPadding(dp(if (isTv) 28 else 9), dp(6), dp(if (isTv) 28 else 9), dp(20))
                clipToPadding = false
                setItemViewCacheSize(if (isTv) 16 else 6)
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            if ((tab == "Films" || tab == "Series") &&
                (browsingTotal > browsePageSize || browseOffset > 0)) {
                val paging = Ui.horizontal(this).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(4), dp(12), dp(5))
                }
                if (browseOffset > 0) {
                    paging.addView(Ui.button(this, "‹ Previous", false) {
                        browseOffset = (browseOffset - browsePageSize).coerceAtLeast(0)
                        render()
                    })
                }
                paging.addView(Ui.text(this,
                    "${browseOffset + 1}–${(browseOffset + filtered.size).coerceAtMost(browsingTotal)} of $browsingTotal",
                    12f, Ui.MUTED), LinearLayout.LayoutParams(0, -2, 1f).apply {
                        leftMargin = dp(8)
                    })
                if (browseOffset + filtered.size < browsingTotal) {
                    paging.addView(Ui.button(this, "Next ›", false) {
                        browseOffset += browsePageSize
                        render()
                    })
                }
                layout.addView(paging)
            }
        }
        body.addView(layout, FrameLayout.LayoutParams(-1, -1))
    }

    private fun browseProviderCategories(type: ContentType) {
        val config = AppData.config ?: return
        if (loading) return
        val busy = AlertDialog.Builder(this)
            .setTitle("Reading all provider categories")
            .setMessage("Loading category names only. Your entire library will not be downloaded.")
            .setNegativeButton("Cancel", null)
            .create()
        busy.show()
        worker.execute {
            val result = runCatching { SourceService.providerCategories(config, type) }
            main.post {
                if (isFinishing || isDestroyed || AppData.config != config || !busy.isShowing) return@post
                busy.dismiss()
                result.onSuccess { all -> showProviderCategoryPicker(config, all, type) }
                    .onFailure { e -> AlertDialog.Builder(this)
                        .setTitle("Provider categories unavailable")
                        .setMessage(e.message ?: "Unable to read category names.")
                        .setPositiveButton("OK", null).show()
                    }
            }
        }
    }

    /** Filter all provider categories locally. Loading is limited to a selected group. */
    private fun showProviderCategoryPicker(
        config: ProviderConfig, categories: List<SourceService.ProviderCategory>, type: ContentType
    ) {
        val sorted = categories.sortedBy { it.name.lowercase() }
        var filtered = sorted
        val container = Ui.vertical(this).apply { setPadding(dp(16), dp(10), dp(16), dp(4)) }
        container.addView(Ui.text(this,
            "${sorted.size} categories available. Search for a language (e.g. EN, English, UK) or subtitle type, then tap a category to load its titles.",
            13f, Ui.MUTED), Ui.columnParams(this, 12))
        val filter = EditText(this).apply {
            hint = "Filter categories, languages or subtitles"
            setSingleLine(true)
            setTextColor(Ui.WHITE)
            setHintTextColor(Ui.MUTED)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = Ui.bg(Ui.PANEL, dp(12).toFloat())
        }
        container.addView(filter, Ui.columnParams(this, 12))
        val list = ListView(this).apply {
            isFocusable = true
            dividerHeight = dp(1)
        }
        val labels = mutableListOf<String>()
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = super.getView(position, convertView, parent)
                (row as? TextView)?.apply {
                    setTextColor(Ui.WHITE)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                return row
            }
        }
        list.adapter = adapter
        fun display(matches: List<SourceService.ProviderCategory>) {
            filtered = matches
            labels.clear()
            labels.addAll(matches.map { it.name })
            adapter.notifyDataSetChanged()
        }
        display(sorted)
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty().trim()
                display(if (query.isBlank()) sorted else sorted.filter {
                    it.name.contains(query, ignoreCase = true)
                })
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        container.addView(list, LinearLayout.LayoutParams(-1, dp(if (isTv) 400 else 350)))
        val picker = AlertDialog.Builder(this)
            .setTitle(if (type == ContentType.SERIES) "All series categories" else "All film categories")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            val selected = filtered.getOrNull(position) ?: return@setOnItemClickListener
            picker.dismiss()
            loadProviderCategory(config, selected)
        }
        picker.show()
    }

    private fun loadProviderCategory(config: ProviderConfig, selected: SourceService.ProviderCategory) {
        if (loading) return
        loading = true
        errorText = ""
        render()
        worker.execute {
            try {
                if (!catalogue.isComplete(selected.type, selected.id)) {
                    val fetched = SourceService.loadProviderCategory(config, selected)
                    if (AppData.config != config) return@execute
                    catalogue.storeCategory(selected, fetched)
                }
                main.post {
                    if (isFinishing || isDestroyed || AppData.config != config) return@post
                    loading = false
                    tab = if (selected.type == ContentType.SERIES) "Series" else "Films"
                    category = selected.name
                    browseOffset = 0
                    searchBox.hint = searchHint()
                    clearSearch()
                }
            } catch (e: Exception) {
                main.post {
                    if (isFinishing || isDestroyed || AppData.config != config) return@post
                    loading = false
                    render()
                    AlertDialog.Builder(this).setTitle("Category could not be loaded")
                        .setMessage(e.message ?: "Unable to retrieve category contents")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private inner class MediaAdapter(private val data: List<MediaEntry>, private val horizontal: Boolean) :
        RecyclerView.Adapter<MediaAdapter.Holder>() {
        inner class Holder(val column: LinearLayout, val art: ImageView, val name: TextView, val sub: TextView) :
            RecyclerView.ViewHolder(column)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val column = Ui.vertical(this@MainActivity).apply {
                isClickable = true
                isFocusable = true
                Ui.tvFocus(this)
            }
            val width = if (horizontal) Ui.artworkCardWidth(this@MainActivity) else ViewGroup.LayoutParams.MATCH_PARENT
            column.layoutParams = RecyclerView.LayoutParams(width, dp(if (isTv) 268 else 231)).apply {
                leftMargin = dp(5); rightMargin = dp(5); bottomMargin = dp(9)
            }
            val frame = FrameLayout(this@MainActivity).apply {
                background = Ui.gradient(dp(15).toFloat()); clipToOutline = true
            }
            val art = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            frame.addView(art, FrameLayout.LayoutParams(-1, -1))
            frame.addView(Ui.text(this@MainActivity, "▶", 27f, 0xDDF5F6FC.toInt(), true).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
            column.addView(frame, LinearLayout.LayoutParams(-1, dp(if (isTv) 195 else 174)))
            val name = Ui.text(this@MainActivity, "", if (isTv) 16f else 13f, Ui.WHITE, true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(3), dp(6), dp(2), 0)
            }
            val sub = Ui.text(this@MainActivity, "", if (isTv) 12.5f else 10.5f, Ui.MUTED).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(3), 0, dp(2), 0)
            }
            column.addView(name)
            column.addView(sub)
            return Holder(column, art, name, sub)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = data[position]
            holder.name.text = e.title
            val epgNow = if (e.type == ContentType.LIVE) AppData.schedules[e.epgId]?.now?.title else null
            holder.sub.text = epgNow ?: e.group
            ImageLoader.into(holder.art, e.image)
            holder.column.setOnClickListener { showDetails(e) }
            holder.column.contentDescription = "${e.title}, ${e.group}"
            holder.column.setOnLongClickListener {
                val fav = storage.toggleFavorite(e.id)
                Toast.makeText(this@MainActivity, if (fav) "Added to My List" else "Removed from My List", Toast.LENGTH_SHORT).show()
                if (tab == "My List") render()
                true
            }
        }
        override fun getItemCount() = data.size
    }

    private fun showDetails(entry: MediaEntry) {
        val column = Ui.vertical(this).apply { setPadding(dp(18), dp(16), dp(18), dp(16)); setBackgroundColor(Ui.SURFACE) }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Ui.gradient(dp(14).toFloat()) }
        column.addView(image, LinearLayout.LayoutParams(-1, dp(190)))
        ImageLoader.into(image, entry.backdrop.ifBlank { entry.image })
        column.addView(Ui.text(this, entry.title, 22f, Ui.WHITE, true), Ui.columnParams(this, 9, 15))
        column.addView(Ui.text(this, "${entry.type.name}  •  ${entry.group}", 12f, Ui.MINT), Ui.columnParams(this, 9))
        val description = AppData.schedules[entry.epgId]?.now?.let { "On now: ${it.title}" }
            ?: entry.description.ifBlank { "Enjoy this title from your connected library." }
        column.addView(Ui.text(this, description, 13f, Ui.MUTED), Ui.columnParams(this, 16))
        val dialog = AlertDialog.Builder(this).setView(column).create()
        column.addView(Ui.button(this, if (entry.type == ContentType.SERIES) "View episodes" else "▶  Watch now") {
            dialog.dismiss(); open(entry)
        }, Ui.columnParams(this, 10))
        column.addView(Ui.button(this, if (storage.isFavorite(entry.id)) "♥  Remove from My List" else "♡  Add to My List", false) {
            val added = storage.toggleFavorite(entry.id)
            Toast.makeText(this, if (added) "Added to My List" else "Removed from My List", Toast.LENGTH_SHORT).show()
            dialog.dismiss(); render()
        })
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * (if (isTv) .67 else .92)).toInt(), -2)
    }

    private fun open(entry: MediaEntry) {
        val klass = if (entry.type == ContentType.SERIES) SeriesActivity::class.java else PlayerActivity::class.java
        startActivity(Intent(this, klass).putExtra("entry", entry.toJson().toString()))
    }

    private fun connectDialog(kind: String) {
        val current = AppData.config?.takeIf { it.kind == kind }
        val fields = Ui.vertical(this).apply { setPadding(dp(20), dp(14), dp(20), dp(4)) }
        val name = input("Provider name", current?.name ?: if (kind == "m3u") "My playlist" else "My IPTV", fields)
        val url = input(if (kind == "m3u") "M3U URL (https://…)" else "Server URL (https://host:port)", current?.url.orEmpty(), fields)
        val user = if (kind == "xtream") input("Username", current?.username.orEmpty(), fields) else null
        val pass = if (kind == "xtream") input("Password", current?.password.orEmpty(), fields).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else null
        val epg = input("XMLTV EPG URL (optional)", current?.epgUrl.orEmpty(), fields)
        val scroll = ScrollView(this).apply { addView(fields) }
        val dialog = AlertDialog.Builder(this).setTitle(if (kind == "m3u") "Import M3U playlist" else "Connect Xtream provider")
            .setView(scroll).setNegativeButton("Cancel", null)
            .setPositiveButton("Connect", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val server = url.text.toString().trim().trimEnd('/')
                if (!Net.validHttp(server)) { url.error = "Enter a valid http(s) address"; return@setOnClickListener }
                if (kind == "xtream" && (user?.text.isNullOrBlank() || pass?.text.isNullOrBlank())) {
                    Toast.makeText(this, "Enter your provider username and password", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val epgUrl = epg.text.toString().trim()
                if (epgUrl.isNotEmpty() && !Net.validHttp(epgUrl)) { epg.error = "Enter a valid URL"; return@setOnClickListener }
                dialog.dismiss()
                connect(ProviderConfig(kind, name.text.toString().trim().ifBlank { "My IPTV" }, server,
                    user?.text?.toString()?.trim().orEmpty(), pass?.text?.toString().orEmpty(), epgUrl))
            }
        }
        dialog.show()
    }

    private fun input(hint: String, defaultValue: String, parent: LinearLayout): EditText {
        val input = EditText(this).apply {
            setSingleLine(true); setTextColor(Ui.WHITE); setHintTextColor(Ui.MUTED); textSize = 14f
            this.hint = hint; setText(defaultValue)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = Ui.bg(Ui.PANEL, dp(10).toFloat())
        }
        parent.addView(input, Ui.columnParams(this, 11))
        return input
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, 3001)
    }

    @Deprecated("Android activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3001 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            connect(ProviderConfig("file", "Local M3U file", uri.toString()))
        }
    }

    private fun connect(config: ProviderConfig) {
        val old = AppData.config
        val sameSource = old != null && old.kind == config.kind &&
            old.url == config.url && old.username == config.username &&
            old.password == config.password
        cancelImportRequested = true
        if (!sameSource) {
            storage.clearProvider()
            AppData.entries = emptyList()
            AppData.schedules = emptyMap()
            AppData.catalogReport = ""
            AppData.episodeQueue = emptyList(); AppData.episodeQueueFor = ""
        }
        AppData.config = config
        storage.saveProvider(config)
        cancelImportRequested = false
        liveLoading = false
        liveFailure = ""
        loadingDone = 0; loadingTotal = 0
        refresh()
    }

    private fun refresh() {
        val config = AppData.config ?: return
        if (loading) return
        if (config.kind == "xtream") {
            // Xtream category import must NEVER depend on the Live TV endpoint.
            // Let users navigate immediately, even while all categories download.
            cancelImportRequested = false
            loading = false
            importing = true
            loadingMessage = "Reading Film and Series categories"
            loadingDone = 0; loadingTotal = 0
            initialTitlesRendered = false
            liveLoading = false
            errorText = ""
            storage.markCatalogRefreshed()
            render()
            worker.execute {
                try {
                    importCompleteLibrary(config)
                } catch (error: Exception) {
                    main.post {
                        if (isFinishing || isDestroyed || AppData.config != config) return@post
                        importing = false
                        loading = false
                        errorText = SourceService.importErrorMessage(error)
                        updateProgressViews()
                        render()
                    }
                }
            }
            // When requested, Live TV will load on its own executor via the Live tab.
            if (tab == "Live TV") requestLiveChannels()
            return
        }
        loading = true
        cancelImportRequested = false
        loadingMessage = "Connecting to your playlist"
        loadingDone = 0; loadingTotal = 0
        importing = SourceService.xtreamFor(config) != null
        errorText = ""
        render()
        worker.execute {
            try {
                // Bootstrap Live TV without importing and duplicating 80 categories of VOD.
                val result = if (config.kind == "xtream")
                    SourceService.loadXtreamLive(config) { stage ->
                        main.post {
                            if (!isFinishing && !isDestroyed && AppData.config == config) {
                                loadingMessage = stage
                                updateProgressViews()
                            }
                        }
                    } else SourceService.loadDetailed(this, config)
                if (AppData.config != config || cancelImportRequested) return@execute
                storage.saveCatalog(result.entries)
                storage.saveCatalogReport(result.report)
                storage.markCatalogRefreshed()
                AppData.entries = result.entries
                AppData.catalogReport = result.report
                if (SourceService.xtreamFor(config) != null) {
                    // Users can browse the Live TV preview immediately. Keep
                    // downloading all remaining VOD categories behind the UI.
                    if (result.entries.isNotEmpty()) {
                        main.post {
                            if (!isFinishing && !isDestroyed && AppData.config == config) {
                                loading = false
                                render()
                            }
                        }
                    }
                    importCompleteLibrary(config)
                } else {
                    main.post {
                        if (isFinishing || isDestroyed || AppData.config != config) return@post
                        loading = false
                        category = "All"
                        render()
                    }
                }
                if (SourceService.epgUrl(config).isNotBlank()) fetchEpg(config, result.entries)
            } catch (e: Exception) {
                main.post {
                    if (isFinishing || isDestroyed || AppData.config != config) return@post
                    importing = false
                    loading = false
                    updateProgressViews()
                    errorText = e.message ?: "Failed to connect to the source"
                    render()
                    Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun resumeFullImport(config: ProviderConfig) {
        if (loading) return
        // The last update trapped returning users behind an all-screen spinner
        // whenever an import was incomplete. Keep the existing library usable.
        loading = false
        loadingMessage = "Resuming unfinished catalogue"
        loadingDone = 0; loadingTotal = 0
        importing = true
        cancelImportRequested = false
        initialTitlesRendered = false
        updateProgressViews()
        worker.execute {
            try { importCompleteLibrary(config) }
            catch (e: Exception) {
                main.post {
                    if (isFinishing || isDestroyed || AppData.config != config) return@post
                    importing = false
                    loading = false
                    errorText = SourceService.importErrorMessage(e)
                    updateProgressViews()
                    render()
                }
            }
        }
    }

    private fun importCompleteLibrary(config: ProviderConfig) {
        progressEvents = 0
        importing = true
        main.post { updateProgressViews() }
        var lastPostedAt = 0L
        val outcome = SourceService.importAllVod(config, catalogue, { progress ->
            progressEvents++
            val now = android.os.SystemClock.elapsedRealtime()
            // Throttle UI updates, not downloads. Never reconstruct the grid or
            // D-pad focus during background progress.
            if (progressEvents == 1 || now - lastPostedAt >= 400L ||
                progress.done + progress.failed == progress.total) {
                lastPostedAt = now
                main.post {
                    if (isFinishing || isDestroyed || AppData.config != config) return@post
                    loadingDone = progress.done; loadingTotal = progress.total
                    loadingMessage = progress.category
                    loadingFilms = progress.films; loadingShows = progress.series
                    loadingFailures = progress.failed
                    if (loading && (progress.films + progress.series > 0)) {
                        loading = false
                        render()
                    } else if (!initialTitlesRendered &&
                        (progress.films + progress.series > 0)) {
                        // Show the first batch immediately, then preserve focus.
                        initialTitlesRendered = true
                        if (tab == "Home" || tab == "Films" || tab == "Series") render()
                    }
                    updateProgressViews()
                }
            }
        }, isCancelled = { cancelImportRequested || AppData.config != config ||
            Thread.currentThread().isInterrupted },
           parallelRequests = if (isTv) 3 else 4)
        storage.markFullImportComplete(outcome.total > 0 &&
            outcome.done == outcome.total && outcome.failed == 0)
        val report = "Full provider import\nFilms: ${outcome.films}\n" +
            "Series: ${outcome.series}\n" +
            "Categories completed: ${outcome.done}/${outcome.total}\n" +
            "Failed this attempt: ${outcome.failed}\n" +
            "If the import was interrupted, reopen the app to retry outstanding categories."
        storage.saveCatalogReport(report)
        main.post {
            if (isFinishing || isDestroyed || AppData.config != config) return@post
            AppData.catalogReport = report
            loadingDone = outcome.done; loadingTotal = outcome.total
            loadingFilms = outcome.films; loadingShows = outcome.series
            loadingFailures = outcome.failed
            importing = false
            val wasLoading = loading
            loading = false
            if (wasLoading) category = "All"
            updateProgressViews()
            if (wasLoading || tab == "Home" || tab == "Films" || tab == "Series") render()
            if (outcome.failed > 0) Toast.makeText(this,
                "Some provider categories failed. Reopen the app to retry.", Toast.LENGTH_LONG).show()
        }
    }

    private fun fetchEpg(config: ProviderConfig, entries: List<MediaEntry>) {
        worker.execute {
            runCatching { EpgService.load(SourceService.epgUrl(config), entries) }.onSuccess { schedules ->
                main.post { if (AppData.config == config) { AppData.schedules = schedules; if (!isFinishing) render() } }
            }.onFailure { e ->
                main.post { Toast.makeText(this, "EPG unavailable: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showSettings() {
        val config = AppData.config
        val panel = Ui.vertical(this).apply { setPadding(dp(20), dp(14), dp(20), dp(10)) }
        panel.addView(Ui.text(this, "LUMATV  •  SETTINGS", 19f, Ui.WHITE, true), Ui.columnParams(this, 12))
        panel.addView(Ui.text(this, if (config == null) "No source connected" else "Connected: ${config.name}  (${config.kind.uppercase()})", 13f, Ui.MUTED), Ui.columnParams(this, 14))
        var epg: EditText? = null
        if (config != null) {
            epg = input("XMLTV EPG URL (optional)", config.epgUrl, panel)
            panel.addView(Ui.text(this, "For some Xtream providers, change Live format to TS if HLS playback is unavailable.", 12f, Ui.MUTED), Ui.columnParams(this, 10))
        }
        val dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(panel) }).create()
        if (config != null) {
            panel.addView(Ui.button(this, "Catalogue diagnostics (Live / Films / Series)", false) {
                dialog.dismiss()
                val report = AppData.catalogReport.ifBlank {
                    "No import report saved yet. Tap Refresh library to check your provider's Live TV, Films and Series API responses."
                }
                AlertDialog.Builder(this)
                    .setTitle("Catalogue import report")
                    .setMessage(report)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Refresh library") { _, _ -> refresh() }
                    .show()
            }, Ui.columnParams(this, 9))
            panel.addView(Ui.button(this, "Manage visible channels", false) {
                dialog.dismiss()
                startActivity(Intent(this, ChannelManagerActivity::class.java))
            }, Ui.columnParams(this, 9))
            panel.addView(Ui.button(this, if (storage.autoplayEnabled()) "Autoplay next episode: ON" else "Autoplay next episode: OFF", false) {
                storage.setAutoplayEnabled(!storage.autoplayEnabled())
                dialog.dismiss()
                showSettings()
            }, Ui.columnParams(this, 9))
            panel.addView(Ui.button(this, "Save guide URL") {
                val value = epg?.text?.toString()?.trim().orEmpty()
                if (value.isNotBlank() && !Net.validHttp(value)) { epg?.error = "Valid URL required"; return@button }
                val updated = config.copy(epgUrl = value)
                storage.saveProvider(updated); AppData.config = updated
                dialog.dismiss()
                if (SourceService.epgUrl(updated).isNotBlank()) fetchEpg(updated, AppData.entries)
                else { AppData.schedules = emptyMap(); render() }
            }, Ui.columnParams(this, 9))
            if (config.kind == "xtream") {
                panel.addView(Ui.button(this, "Live stream type: ${config.liveFormat.uppercase()}  (tap to change)", false) {
                    val updated = config.copy(liveFormat = if (config.liveFormat == "m3u8") "ts" else "m3u8")
                    storage.saveProvider(updated); AppData.config = updated
                    dialog.dismiss(); refresh()
                }, Ui.columnParams(this, 9))
            }
            panel.addView(Ui.button(this, "↻  Refresh library", false) { dialog.dismiss(); refresh() }, Ui.columnParams(this, 9))
            panel.addView(Ui.button(this, "Disconnect source", false) {
                dialog.dismiss()
                AlertDialog.Builder(this).setTitle("Disconnect?")
                    .setMessage("Your source configuration and cached catalogue will be cleared. Your favourites and watch history will remain on this device.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Disconnect") { _, _ -> disconnect() }.show()
            }, Ui.columnParams(this, 9))
        } else {
            panel.addView(Ui.button(this, "Add a source") { dialog.dismiss(); showWelcome() })
        }
        panel.addView(Ui.button(this, "Last crash report (safe to share)", false) {
            dialog.dismiss()
            val details = CrashReporter.lastReport(this).ifBlank {
                "No crash report recorded by this version yet. If importing still crashes, reopen LumaTV and view this screen."
            }
            AlertDialog.Builder(this).setTitle("LumaTV crash diagnostics")
                .setMessage(details)
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy report") { _, _ ->
                    val clip = android.content.ClipData.newPlainText("LumaTV crash report", details)
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(clip)
                    Toast.makeText(this, "Crash report copied", Toast.LENGTH_SHORT).show()
                }.show()
        }, Ui.columnParams(this, 9))
        panel.addView(Ui.text(this, "LumaTV 1.4.0 • Android & Fire TV • Local settings", 11f, Ui.MUTED), Ui.columnParams(this, 9))
        dialog.show()
    }

    private fun disconnect() {
        storage.clearProvider()
        AppData.config = null; AppData.entries = emptyList(); AppData.schedules = emptyMap()
        AppData.catalogReport = ""
        AppData.episodeQueue = emptyList(); AppData.episodeQueueFor = ""
        tab = "Home"; category = "All"; errorText = ""
        clearSearch()
        render()
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + .5f).toInt()
}
