package com.lumatv.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
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

    fun saveCatalog(entries: List<MediaEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        val tmp = File(context.filesDir, "catalog.tmp")
        tmp.writeBytes(seal(array.toString().toByteArray(Charsets.UTF_8)))
        val dest = File(context.filesDir, "catalog.enc")
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
    }

    fun loadCatalog(): List<MediaEntry> = runCatching {
        val file = File(context.filesDir, "catalog.enc")
        if (!file.exists()) return emptyList()
        JSONArray(String(unseal(file.readBytes()), Charsets.UTF_8)).asObjects().map(MediaEntry::fromJson)
    }.getOrDefault(emptyList())

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
        prefs.edit().remove("provider").apply()
        File(context.filesDir, "catalog.enc").delete()
        File(context.filesDir, "catalog.json").delete()
        File(context.filesDir, "catalog.tmp").delete()
    }
}
