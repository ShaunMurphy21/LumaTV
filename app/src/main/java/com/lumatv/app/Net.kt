package com.lumatv.app

import android.net.Uri
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Net {
    fun validHttp(url: String): Boolean = runCatching {
        val u = URL(url)
        (u.protocol == "http" || u.protocol == "https") && !u.host.isNullOrBlank()
    }.getOrDefault(false)

    fun open(url: String, timeoutMs: Int = 20000): Pair<HttpURLConnection, InputStream> {
        require(validHttp(url)) { "A valid HTTP or HTTPS address is required." }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "LumaTV/1.0 (Android IPTV Client)")
        c.setRequestProperty("Accept-Encoding", "gzip")
        try {
            if (c.responseCode !in 200..299) error("Server returned HTTP ${c.responseCode}")
            val raw = c.inputStream
            val zipped = c.contentEncoding?.contains("gzip", ignoreCase = true) == true ||
                url.substringBefore('?').endsWith(".gz", ignoreCase = true)
            return c to if (zipped) GZIPInputStream(raw) else raw
        } catch (e: Exception) {
            c.disconnect()
            throw e
        }
    }

    fun readText(url: String, maxBytes: Int = 24_000_000): String {
        val (connection, stream) = open(url)
        try {
            stream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16_384)
                while (true) {
                    val n = input.read(chunk)
                    if (n == -1) break
                    if (out.size() + n > maxBytes) error("Response exceeds supported limit.")
                    out.write(chunk, 0, n)
                }
                return out.toString(Charsets.UTF_8.name())
            }
        } finally { connection.disconnect() }
    }

    fun urlEncode(s: String): String = Uri.encode(s)
}
