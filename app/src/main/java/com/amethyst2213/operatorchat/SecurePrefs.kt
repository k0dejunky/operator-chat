package com.amethyst2213.operatorchat

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-backed storage for the bridge URL and token. */
object SecurePrefs {

    private const val STORE = "operator_chat_secure"
    private const val KEY_ALIAS = "operator_chat_credentials"
    private const val URL_KEY = "url"
    private const val TOKEN_KEY = "token"
    private const val IV_SUFFIX = ".iv"
    private const val LEGACY_STORE = "operator_chat"

    fun url(context: Context): String = read(context, URL_KEY) ?: migrate(context, URL_KEY)

    fun token(context: Context): String = read(context, TOKEN_KEY) ?: migrate(context, TOKEN_KEY)

    fun save(context: Context, url: String, token: String) {
        write(context, URL_KEY, url)
        write(context, TOKEN_KEY, token)
        legacyPrefs(context).edit().remove(URL_KEY).remove(TOKEN_KEY).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .remove(URL_KEY).remove(URL_KEY + IV_SUFFIX)
            .remove(TOKEN_KEY).remove(TOKEN_KEY + IV_SUFFIX).apply()
        legacyPrefs(context).edit().remove(URL_KEY).remove(TOKEN_KEY).apply()
    }

    private fun migrate(context: Context, key: String): String {
        val legacy = legacyPrefs(context).getString(key, "").orEmpty()
        if (legacy.isNotEmpty()) {
            write(context, key, legacy)
            legacyPrefs(context).edit().remove(key).apply()
        }
        return legacy
    }

    private fun legacyPrefs(context: Context) =
        context.getSharedPreferences(LEGACY_STORE, Context.MODE_PRIVATE)

    private fun read(context: Context, key: String): String? {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val encoded = prefs.getString(key, null) ?: return null
        val ivEncoded = prefs.getString(key + IV_SUFFIX, null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyFor(),
                GCMParameterSpec(128, Base64.decode(ivEncoded, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun write(context: Context, key: String, value: String) {
        if (value.isEmpty()) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFor())
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString(key, Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP))
            .putString(key + IV_SUFFIX, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    private fun keyFor(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }
}
