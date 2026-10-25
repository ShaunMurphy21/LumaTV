package com.lumatv.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Storage(private val context: Context) {
    private val prefs = context.getSharedPreferences("lumatv_user_v1", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val alias = "lumatv_source_v1"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return generator.generateKey()
    }

    fun saveProvider(config: ProviderConfig) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(config.toJson().toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("provider", Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)).apply()
    }

    fun loadProvider(): ProviderConfig? = runCatching {
        val packed = Base64.decode(prefs.getString("provider", "") ?: "", Base64.NO_WRAP)
        if (packed.size < 29) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        val json = cipher.doFinal(packed.copyOfRange(12, packed.size))
        ProviderConfig.fromJson(JSONObject(String(json, Charsets.UTF_8)))
    }.getOrNull()

    private fun seal(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(data)
    }

    private fun unseal(packed: ByteArray): ByteArray {
        require(packed.size > 28) { "Encrypted library data is incomplete" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return cipher.doFinal(packed.copyOfRange(12, packed.size))
    }

    // Force a one-time catalogue refresh when switching from the earlier M3U classifier.
    fun needsCatalogRefresh(): Boolean = prefs.getInt("catalog_parser_version", 0) < 4
    fun markCatalogRefreshed() { prefs.edit().putInt("catalog_parser_version", 4).apply() }

    fun saveCatalogReport(report: String) { prefs.edit().putString("catalog_report", report).apply() }
    fun loadCatalogReport(): String = prefs.getString("catalog_report", "") ?: ""

    // Write each item as it arrives rather than constructing a giant JSONArray,
    // an equally large UTF-8 String and another encrypted byte array in memory.
    fun saveCatalog(entries: List<MediaEntry>) {
        val tmp = File(context.filesDir, "catalog.tmp")
        val dest = File(context.filesDir, "catalog.enc")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        FileOutputStream(tmp).use { output ->
            output.write(cipher.iv)
            OutputStreamWriter(CipherOutputStream(output, cipher), Charsets.UTF_8).use { writer ->
                writer.write("[")
                entries.forEachIndexed { index, entry ->
                    if (index != 0) writer.write(",")
                    writer.write(entry.toJson().toString())
                }
                writer.write("]")
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    // Read the old and new encrypted JSON formats with a streaming parser.
    // This also supports an existing v1.2.0 cache without deleting user data.
    fun loadCatalog(): List<MediaEntry> = runCatching {
        val file = File(context.filesDir, "catalog.enc")
        if (!file.exists()) return@runCatching emptyList()
        FileInputStream(file).use { source ->
            val iv = ByteArray(12)
            var offset = 0
            while (offset < iv.size) {
                val n = source.read(iv, offset, iv.size - offset)
                if (n < 0) error("Encrypted catalogue has an incomplete header")
                offset += n
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            JsonReader(InputStreamReader(CipherInputStream(source, cipher), Charsets.UTF_8)).use { json ->
                val restored = ArrayList<MediaEntry>()
                json.beginArray()
                while (json.hasNext()) {
                    if (restored.size >= 55_000) error("Saved catalogue exceeds the device safety limit")
                    var id = ""; var title = ""; var url = ""; var type = ContentType.LIVE
                    var group = "Other"; var image = ""; var description = ""; var epgId = ""
                    var seriesId = ""; var season = 0; var episode = 0; var backdrop = ""
                    var agent = ""; var referrer = ""
                    json.beginObject()
                    while (json.hasNext()) {
                        when (json.nextName()) {
                            "id" -> id = json.nextString()
                            "title" -> title = json.nextString()
                            "url" -> url = json.nextString()
                            "type" -> type = runCatching { ContentType.valueOf(json.nextString()) }
                                .getOrDefault(ContentType.LIVE)
                            "group" -> group = json.nextString()
                            "image" -> image = json.nextString()
                            "description" -> description = json.nextString()
                            "epgId" -> epgId = json.nextString()
                            "seriesId" -> seriesId = json.nextString()
                            "season" -> season = json.nextInt()
                            "episode" -> episode = json.nextInt()
                            "backdrop" -> backdrop = json.nextString()
                            "httpUserAgent" -> agent = json.nextString()
                            "httpReferrer" -> referrer = json.nextString()
                            else -> json.skipValue()
                        }
                    }
                    json.endObject()
                    restored.add(MediaEntry(id, title, url, type, group, image, description,
                        epgId, seriesId, season, episode, backdrop, agent, referrer))
                }
                json.endArray()
                restored
            }
        }
    }.getOrDefault(emptyList())

    // Persist across refreshes and app upgrades. User-managed channel visibility is local to device.
    fun hiddenChannelIds(): Set<String> = prefs.getStringSet("hidden_channel_ids", emptySet())?.toSet() ?: emptySet()
    fun isChannelHidden(id: String): Boolean = hiddenChannelIds().contains(id)
    fun setChannelHidden(id: String, hidden: Boolean) = setHiddenChannels(listOf(id), hidden)
    fun setHiddenChannels(ids: Collection<String>, hidden: Boolean) {
        val next = hiddenChannelIds().toMutableSet()
        if (hidden) next.addAll(ids) else next.removeAll(ids.toSet())
        prefs.edit().putStringSet("hidden_channel_ids", next).apply()
    }
    fun visibleEntries(entries: List<MediaEntry>): List<MediaEntry> {
        val hidden = hiddenChannelIds()
        return entries.filter { it.type != ContentType.LIVE || it.id !in hidden }
    }
    fun autoplayEnabled(): Boolean = prefs.getBoolean("autoplay_episodes", true)
    fun setAutoplayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("autoplay_episodes", enabled).apply()
    }

    fun isFavorite(id: String): Boolean = prefs.getBoolean("fav_$id", false)
    fun toggleFavorite(id: String): Boolean {
        val value = !isFavorite(id)
        prefs.edit().putBoolean("fav_$id", value).apply()
        return value
    }
    fun favorites(entries: List<MediaEntry>): List<MediaEntry> = entries.filter { isFavorite(it.id) }

    fun savePosition(id: String, positionMs: Long, durationMs: Long) {
        prefs.edit().putLong("pos_$id", positionMs.coerceAtLeast(0))
            .putLong("dur_$id", durationMs.coerceAtLeast(0))
            .putLong("seen_$id", System.currentTimeMillis()).apply()
    }
    fun position(id: String): Long = prefs.getLong("pos_$id", 0L)
    fun duration(id: String): Long = prefs.getLong("dur_$id", 0L)
    fun recents(entries: List<MediaEntry>): List<MediaEntry> = entries
        .filter {
            it.type != ContentType.LIVE && prefs.getLong("seen_${it.id}", 0L) > 0L &&
                position(it.id) > 10_000L &&
                (duration(it.id) <= 0 || position(it.id) < duration(it.id) - 15_000L)
        }
        .sortedByDescending { prefs.getLong("seen_${it.id}", 0L) }
        .take(30)

    fun clearProvider() {
        prefs.edit().remove("provider").remove("catalog_report").apply()
        File(context.filesDir, "catalog.enc").delete()
        File(context.filesDir, "catalog.json").delete()
        File(context.filesDir, "catalog.tmp").delete()
    }
}
