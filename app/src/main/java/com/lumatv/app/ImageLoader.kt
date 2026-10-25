package com.lumatv.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

object ImageLoader {
    private val executor = Executors.newFixedThreadPool(4)
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun into(view: ImageView, url: String) {
        view.tag = url
        view.setImageDrawable(null)
        if (!Net.validHttp(url)) return
        cache.get(url)?.let { view.setImageBitmap(it); return }
        executor.execute {
            runCatching {
                val (connection, input) = Net.open(url, 13_000)
                try {
                    val bytes = input.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = stream.read(chunk)
                            if (n < 0) break
                            if (out.size() + n > 5_000_000) error("Artwork exceeds image limit")
                            out.write(chunk, 0, n)
                        }
                        out.toByteArray()
                    }
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    val factor = (maxOf(options.outWidth / 600, options.outHeight / 700, 1)).coerceAtLeast(1)
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                        inSampleSize = factor
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }) ?: return@runCatching
                    cache.put(url, decoded)
                    view.post { if (view.tag == url) view.setImageBitmap(decoded) }
                } finally { connection.disconnect() }
            }
        }
    }
}
