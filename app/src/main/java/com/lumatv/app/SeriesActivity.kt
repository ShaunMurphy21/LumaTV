package com.lumatv.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors

class SeriesActivity : Activity() {
    private lateinit var show: MediaEntry
    private lateinit var storage: Storage
    private lateinit var column: LinearLayout
    private lateinit var episodeArea: LinearLayout
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var episodes: List<MediaEntry> = emptyList()
    private var chosenSeason = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        show = runCatching { MediaEntry.fromJson(JSONObject(intent.getStringExtra("entry") ?: "")) }.getOrElse {
            finish(); return
        }
        window.statusBarColor = Ui.BG
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        column = Ui.vertical(this)
        scroll.addView(column)
        setContentView(scroll)
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                scroll.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
            scroll.requestApplyInsets()
        }
        renderHeader()
        loadEpisodes()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun renderHeader() {
        column.removeAllViews()
        val banner = FrameLayout(this).apply { background = Ui.gradient(dp(12).toFloat()); clipToOutline = true }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .58f }
        banner.addView(image, FrameLayout.LayoutParams(-1, -1))
        ImageLoader.into(image, show.backdrop.ifBlank { show.image })
        banner.addView(Ui.button(this, "‹  Back", false) { finish() }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(14); leftMargin = dp(14)
        })
        column.addView(banner, LinearLayout.LayoutParams(-1, dp(220)))
        val about = Ui.vertical(this).apply { setPadding(dp(20), dp(17), dp(20), dp(6)) }
        about.addView(Ui.text(this, "SERIES IN YOUR LIBRARY", 10f, Ui.MINT, true), Ui.columnParams(this, 7))
        about.addView(Ui.text(this, show.title, 28f, Ui.WHITE, true), Ui.columnParams(this, 6))
        about.addView(Ui.text(this, "SERIES  •  ${show.group}", 12f, Ui.MUTED), Ui.columnParams(this, 11))
        if (show.description.isNotBlank()) about.addView(Ui.text(this, show.description, 14f, Ui.WHITE), Ui.columnParams(this, 17))
        // Keep different language and subtitle editions as distinct playable
        // series, while making it easy to switch between versions of a show.
        val key = SeriesVariants.canonicalTitle(show.title)
        val versions = AppData.entries.asSequence()
            .filter { it.type == ContentType.SERIES && SeriesVariants.canonicalTitle(it.title) == key }
            .distinctBy { it.id }
            .take(60)
            .sortedWith(compareBy({ it.title.lowercase() }, { it.group.lowercase() }))
            .toList()
        if (versions.size > 1) {
            about.addView(Ui.text(this, "Available languages & editions (${versions.size})",
                15f, Ui.MINT, true), Ui.columnParams(this, 10))
            val versionScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val versionButtons = Ui.horizontal(this)
            versions.forEach { option ->
                val label = if (versions.count { it.title == option.title } > 1)
                    "${option.title} • ${option.group}" else option.title
                versionButtons.addView(Ui.button(this, label, option.id == show.id) {
                    if (option.id != show.id) {
                        show = option
                        episodes = emptyList()
                        AppData.episodeQueue = emptyList()
                        AppData.episodeQueueFor = ""
                        renderHeader()
                        loadEpisodes()
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(9) })
            }
            versionScroll.addView(versionButtons)
            about.addView(versionScroll, Ui.columnParams(this, 13))
        }
        about.addView(Ui.button(this, if (storage.isFavorite(show.id)) "♥  In My List" else "♡  Add to My List", false) {
            storage.toggleFavorite(show.id)
            Toast.makeText(this, "My List updated", Toast.LENGTH_SHORT).show()
            renderHeader()
            showEpisodes()
        }, Ui.columnParams(this, 16))
        column.addView(about)
        episodeArea = Ui.vertical(this).apply { setPadding(dp(18), dp(7), dp(18), dp(24)) }
        column.addView(episodeArea)
    }

    private fun loadEpisodes() {
        episodeArea.removeAllViews()
        episodeArea.addView(ProgressBar(this).apply { isIndeterminate = true })
        episodeArea.addView(Ui.text(this, "Loading episodes…", 14f, Ui.MUTED), Ui.columnParams(this, 14))
        val selectedShow = show
        executor.execute {
            try {
                val config = AppData.config ?: storage.loadProvider() ?: error("Provider no longer connected")
                val loaded = SourceService.episodes(config, selectedShow)
                main.post {
                    if (isFinishing || isDestroyed || show.id != selectedShow.id) return@post
                    episodes = loaded
                    AppData.episodeQueueFor = selectedShow.seriesId
                    AppData.episodeQueue = loaded
                    chosenSeason = loaded.firstOrNull()?.season ?: 0
                    showEpisodes()
                }
            } catch (e: Exception) {
                main.post {
                    if (isFinishing || isDestroyed || show.id != selectedShow.id) return@post
                    episodeArea.removeAllViews()
                    episodeArea.addView(Ui.text(this, "Unable to load episodes: ${e.message}", 14f, Ui.MUTED))
                    episodeArea.addView(Ui.button(this, "Try again") { loadEpisodes() }, Ui.columnParams(this, 12))
                }
            }
        }
    }

    private fun showEpisodes() {
        if (!::episodeArea.isInitialized) return
        episodeArea.removeAllViews()
        if (episodes.isEmpty()) {
            episodeArea.addView(Ui.text(this, "No episodes are available from this provider.", 14f, Ui.MUTED))
            return
        }
        episodeArea.addView(Ui.text(this, "Episodes", 23f, Ui.WHITE, true), Ui.columnParams(this, 12))
        val seasons = episodes.map { it.season }.distinct().sorted()
        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = Ui.horizontal(this)
        seasons.forEach { season ->
            val selected = chosenSeason == season
            chips.addView(Ui.button(this, "Season ${if (season == 0) "?" else season}", selected) {
                chosenSeason = season; showEpisodes()
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(9) })
        }
        chipScroll.addView(chips)
        episodeArea.addView(chipScroll, Ui.columnParams(this, 17))
        episodes.filter { it.season == chosenSeason }.forEach { ep ->
            val row = Ui.horizontal(this).apply {
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = Ui.bg(Ui.SURFACE, dp(12).toFloat())
                isClickable = true
                isFocusable = true
                Ui.tvFocus(this)
                setOnClickListener {
                    startActivity(Intent(this@SeriesActivity, PlayerActivity::class.java).putExtra("entry", ep.toJson().toString()))
                }
            }
            val art = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.gradient(dp(10).toFloat())
                clipToOutline = true
            }
            row.addView(art, LinearLayout.LayoutParams(dp(112), dp(74)))
            ImageLoader.into(art, ep.image)
            val textColumn = Ui.vertical(this).apply { setPadding(dp(12), dp(1), dp(4), dp(2)) }
            textColumn.addView(Ui.text(this, "E${ep.episode}  ${ep.title}", 14f, Ui.WHITE, true).apply { maxLines = 2 })
            if (ep.description.isNotBlank()) textColumn.addView(Ui.text(this, ep.description, 11f, Ui.MUTED).apply { maxLines = 2 }, Ui.columnParams(this, 3))
            val pos = storage.position(ep.id)
            if (pos > 10_000) textColumn.addView(Ui.text(this, "▶  Resume at ${pos / 60_000}m", 11f, Ui.MINT), Ui.columnParams(this, 5))
            row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
            episodeArea.addView(row, Ui.columnParams(this, 10))
        }
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + .5f).toInt()
}
