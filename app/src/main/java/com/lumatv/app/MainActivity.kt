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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var searchBox: EditText
    private lateinit var footer: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var tab = "Home"
    private var category = "All"
    private var search = ""
    private val isTv by lazy { packageManager.hasSystemFeature("android.software.leanback") }
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
                main.post {
                    if (isFinishing || isDestroyed || AppData.config != provider) return@post
                    loading = false
                    if (cached.isEmpty()) {
                        refresh()
                    } else {
                        AppData.entries = cached
                        render()
                        if (SourceService.epgUrl(provider).isNotBlank()) fetchEpg(provider, cached)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized && !loading) render()
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
        root.addView(header)

        searchBox = EditText(this).apply {
            hint = searchHint()
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.WHITE)
            textSize = 14f
            isSingleLine = true
            background = Ui.bg(Ui.SURFACE, dp(15).toFloat())
            setPadding(dp(17), dp(12), dp(12), dp(12))
            setText(search)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    search = s?.toString().orEmpty()
                    if (::body.isInitialized) render()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        val searchRow = Ui.horizontal(this).apply { setPadding(dp(16), 0, dp(16), dp(11)) }
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
        root.addView(footer)
    }

    private fun render() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        footer.removeAllViews()
        val tabs = listOf("Home" to "⌂", "Live TV" to "▣", "Films" to "▶", "Series" to "▤", "My List" to "♥")
        tabs.forEach { (name, symbol) ->
            val selected = tab == name
            val column = Ui.vertical(this).apply {
                gravity = Gravity.CENTER; setPadding(1, dp(2), 1, dp(2)); isClickable = true
                isFocusable = true
                background = Ui.bg(if (selected) Ui.PANEL else 0xFF10131E.toInt(), dp(9).toFloat())
                Ui.tvFocus(this)
                setOnClickListener {
                    tab = name; category = "All"; clearSearch(); searchBox.hint = searchHint(); render()
                }
            }
            column.addView(Ui.text(this, symbol, if (isTv) 23f else 19f, if (selected) Ui.MINT else Ui.MUTED, selected).apply { gravity = Gravity.CENTER })
            column.addView(Ui.text(this, name, if (isTv) 15f else 10f, if (selected) Ui.WHITE else Ui.MUTED, selected).apply { gravity = Gravity.CENTER })
            footer.addView(column, LinearLayout.LayoutParams(0, dp(if (isTv) 70 else 54), 1f))
        }
        val visible = storage.visibleEntries(AppData.entries).filter { it.type != ContentType.EPISODE }
        val scoped = when (tab) {
            "Live TV" -> visible.filter { it.type == ContentType.LIVE }
            "Films" -> visible.filter { it.type == ContentType.MOVIE }
            "Series" -> visible.filter { it.type == ContentType.SERIES }
            "My List" -> storage.favorites(visible)
            else -> visible
        }
        val matches = if (search.isBlank()) scoped else scoped.filter {
            it.title.contains(search, ignoreCase = true) || it.group.contains(search, ignoreCase = true)
        }
        when {
            loading -> showLoading()
            AppData.config == null -> showWelcome()
            AppData.entries.isEmpty() -> showError()
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

    private fun clearSearch() {
        search = ""
        if (::searchBox.isInitialized) {
            if (searchBox.text.isNotEmpty()) searchBox.setText("")
            searchBox.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(searchBox.windowToken, 0)
        }
        if (::body.isInitialized) render()
    }

    private fun consumeBackNavigation(): Boolean {
        when {
            search.isNotBlank() -> clearSearch()
            category != "All" -> { category = "All"; render() }
            tab != "Home" -> { tab = "Home"; searchBox.hint = searchHint(); render() }
            else -> return false
        }
        return true
    }

    @Deprecated("Legacy back key support for Android and Fire OS")
    override fun onBackPressed() {
        if (!consumeBackNavigation()) super.onBackPressed()
    }

    private fun showLoading() {
        val shell = Ui.vertical(this).apply { gravity = Gravity.CENTER }
        shell.addView(ProgressBar(this).apply { isIndeterminate = true })
        shell.addView(Ui.text(this, "Connecting to your library…", 17f, Ui.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(8))
        })
        shell.addView(Ui.text(this, "Importing channels, artwork and categories", 12f, Ui.MUTED).apply { gravity = Gravity.CENTER })
        body.addView(shell, FrameLayout.LayoutParams(-1, -1))
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
        column.addView(Ui.button(this, "Change source", false) { disconnect() })
        scroll.addView(column); body.addView(scroll)
    }

    private fun showHome(list: List<MediaEntry>) {
        if (list.isEmpty()) { showBrowse(list, "Your library"); return }
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val column = Ui.vertical(this)
        val hero = list.firstOrNull { it.type == ContentType.MOVIE && it.image.isNotBlank() }
            ?: list.firstOrNull { it.type == ContentType.SERIES && it.image.isNotBlank() }
            ?: list.first()
        column.addView(makeHero(hero), Ui.columnParams(this, 18))
        val recents = storage.recents(list)
        if (recents.isNotEmpty()) shelf(column, "Continue watching", recents)
        shelf(column, "Live right now", list.filter { it.type == ContentType.LIVE }.take(25))
        shelf(column, "Films for you", list.filter { it.type == ContentType.MOVIE }.take(25))
        val shows = list.filter { it.type == ContentType.SERIES }
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
        parent.addView(rv, LinearLayout.LayoutParams(-1, dp(239)).apply { bottomMargin = dp(11) })
    }

    private fun showBrowse(data: List<MediaEntry>, heading: String) {
        val layout = Ui.vertical(this)
        val headingRow = Ui.horizontal(this).apply { setPadding(dp(16), dp(13), dp(16), dp(14)) }
        headingRow.addView(Ui.text(this, heading, 24f, Ui.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        headingRow.addView(Ui.text(this, "${data.size} titles", 12f, Ui.MUTED))
        layout.addView(headingRow)
        val groups = listOf("All") + data.map { it.group }.distinct().sorted().take(80)
        if (groups.size > 1 && search.isBlank()) {
            val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val chips = Ui.horizontal(this).apply { setPadding(dp(13), 0, dp(13), dp(13)) }
            groups.forEach { name ->
                val chip = Ui.text(this, name, 12f, if (category == name) Ui.BG else Ui.WHITE, category == name).apply {
                    gravity = Gravity.CENTER
                    background = Ui.bg(if (category == name) Ui.MINT else Ui.PANEL, dp(20).toFloat())
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                    isClickable = true
                    setOnClickListener { category = name; render() }
                }
                chips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            }
            chipScroll.addView(chips); layout.addView(chipScroll)
        }
        val filtered = if (category == "All" || search.isNotBlank()) data else data.filter { it.group == category }
        if (filtered.isEmpty()) {
            val message = when (tab) {
                "Films" -> "No films found. Refresh your library in Settings. Your provider must expose VOD films or your M3U must identify film groups."
                "Series" -> "No series found. Refresh your library in Settings. Xtream needs a series catalogue; M3U needs labelled series episodes."
                "Live TV" -> "No visible channels. You may have hidden them in Manage channels."
                else -> "Nothing here yet. Try another category or add a playlist."
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
                resources.configuration.screenWidthDp >= 840 -> 5
                resources.configuration.screenWidthDp >= 600 -> 4
                resources.configuration.screenWidthDp >= 385 -> 3
                else -> 2
            }
            layout.addView(RecyclerView(this).apply {
                layoutManager = GridLayoutManager(this@MainActivity, span)
                adapter = MediaAdapter(filtered, horizontal = false)
                setPadding(dp(9), 0, dp(9), dp(20))
                clipToPadding = false
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        body.addView(layout, FrameLayout.LayoutParams(-1, -1))
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
            column.layoutParams = RecyclerView.LayoutParams(width, dp(231)).apply {
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
            column.addView(frame, LinearLayout.LayoutParams(-1, dp(174)))
            val name = Ui.text(this@MainActivity, "", 13f, Ui.WHITE, true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(3), dp(6), dp(2), 0)
            }
            val sub = Ui.text(this@MainActivity, "", 10.5f, Ui.MUTED).apply {
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
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92).toInt(), -2)
    }

    private fun open(entry: MediaEntry) {
        val klass = if (entry.type == ContentType.SERIES) SeriesActivity::class.java else PlayerActivity::class.java
        startActivity(Intent(this, klass).putExtra("entry", entry.toJson().toString()))
    }

    private fun connectDialog(kind: String) {
        val fields = Ui.vertical(this).apply { setPadding(dp(20), dp(14), dp(20), dp(4)) }
        val name = input("Provider name", if (kind == "m3u") "My playlist" else "My IPTV", fields)
        val url = input(if (kind == "m3u") "M3U URL (https://…)" else "Server URL (https://host:port)", "", fields)
        val user = if (kind == "xtream") input("Username", "", fields) else null
        val pass = if (kind == "xtream") input("Password", "", fields).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else null
        val epg = input("XMLTV EPG URL (optional)", "", fields)
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
        storage.clearProvider()
        AppData.config = config
        AppData.entries = emptyList()
        AppData.schedules = emptyMap()
        AppData.catalogReport = ""
        AppData.episodeQueue = emptyList(); AppData.episodeQueueFor = ""
        storage.saveProvider(config)
        refresh()
    }

    private fun refresh() {
        val config = AppData.config ?: return
        if (loading) return
        loading = true; errorText = ""; render()
        worker.execute {
            try {
                val result = SourceService.loadDetailed(this, config)
                val entries = result.entries
                if (AppData.config != config) return@execute
                storage.saveCatalog(entries)
                storage.saveCatalogReport(result.report)
                storage.markCatalogRefreshed()
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    if (AppData.config != config) return@post
                    AppData.entries = entries
                    AppData.catalogReport = result.report
                    loading = false
                    category = "All"
                    render()
                    if (SourceService.epgUrl(config).isNotBlank()) fetchEpg(config, entries)
                }
            } catch (e: Exception) {
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    if (AppData.config != config) return@post
                    loading = false; errorText = e.message ?: "Failed to connect to the source"
                    render()
                    Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
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
        panel.addView(Ui.text(this, "LumaTV 1.2.1 • Android & Fire TV • Local settings", 11f, Ui.MUTED), Ui.columnParams(this, 9))
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
