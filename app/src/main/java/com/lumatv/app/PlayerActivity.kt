package com.lumatv.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject

@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private lateinit var entry: MediaEntry
    private lateinit var exo: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var toolbar: LinearLayout
    private lateinit var storage: Storage
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private var fillScreen = false
    private val saveTask = object : Runnable {
        override fun run() {
            if (::exo.isInitialized && !finished) {
                saveProgress()
                handler.postDelayed(this, 12_000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entry = runCatching { MediaEntry.fromJson(JSONObject(intent.getStringExtra("entry") ?: "")) }
            .getOrElse { finish(); return }
        if (!Net.validHttp(entry.url)) {
            Toast.makeText(this, "No playable stream URL was provided", Toast.LENGTH_LONG).show()
            finish(); return
        }
        storage = Storage(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 4500
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            keepScreenOn = true
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        toolbar = Ui.horizontal(this).apply {
            setPadding(dp(9), dp(8), dp(9), dp(8))
            setBackgroundColor(0xBB090B12.toInt())
        }
        toolbar.addView(Ui.button(this, "‹", false) { finish() }.apply {
            textSize = 24f; setPadding(dp(13), dp(5), dp(13), dp(5))
        })
        toolbar.addView(Ui.text(this, entry.title, 15f, Ui.WHITE, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(6), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(Ui.button(this, "Fit", false) {
            fillScreen = !fillScreen
            playerView.resizeMode = if (fillScreen) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            toolbar.addView(Ui.button(this, "▣ PiP", false) { pictureInPicture() })
        }
        root.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)
        val httpSource = DefaultHttpDataSource.Factory()
            .setUserAgent(entry.httpUserAgent.ifBlank { "LumaTV/1.0" })
            .setAllowCrossProtocolRedirects(true)
        if (entry.httpReferrer.isNotBlank()) httpSource.setDefaultRequestProperties(mapOf("Referer" to entry.httpReferrer))
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpSource))
            .build()
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity,
                    "Playback error: ${error.errorCodeName}. Check your stream or try TS in Settings.", Toast.LENGTH_LONG).show()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    storage.savePosition(entry.id, 0L, exo.duration.coerceAtLeast(0L))
                }
            }
        })
        exo.setMediaItem(MediaItem.fromUri(entry.url))
        exo.prepare()
        if (entry.type != ContentType.LIVE) {
            val previous = storage.position(entry.id)
            val duration = storage.duration(entry.id)
            if (previous > 10_000L && (duration <= 0 || previous < duration - 20_000L)) exo.seekTo(previous)
        }
        exo.playWhenReady = true
        handler.postDelayed(saveTask, 12_000)
    }

    private fun pictureInPicture() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9)).build())
            }.onFailure {
                Toast.makeText(this, "Picture-in-picture unavailable on this device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::toolbar.isInitialized) toolbar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        if (::playerView.isInitialized) playerView.useController = !isInPictureInPictureMode
    }

    private fun saveProgress() {
        if (::exo.isInitialized && entry.type != ContentType.LIVE && exo.playbackState != Player.STATE_ENDED) {
            val pos = exo.currentPosition
            if (pos >= 0) storage.savePosition(entry.id, pos, exo.duration.coerceAtLeast(0L))
        }
    }

    override fun onPause() {
        saveProgress()
        super.onPause()
    }
    override fun onStop() {
        super.onStop()
        if (::exo.isInitialized && !isInPictureInPictureMode) exo.pause()
    }
    override fun onDestroy() {
        finished = true
        handler.removeCallbacks(saveTask)
        if (::exo.isInitialized) {
            saveProgress()
            playerView.player = null
            exo.release()
        }
        super.onDestroy()
    }
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + .5f).toInt()
}
