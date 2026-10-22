package com.amethyst2213.operatorchat

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed storage for the bridge URL and token.
 *
 * The values are encrypted with a key that lives in the Android keystore, so
 * they cannot be read out of a backup. When the keystore is not available
 * (a broken provider, a locked device) the value falls back to a plain
 * store: losing the credentials would lock the operator out of the inbox,
 * and a working app with a readable token is better than a crash.
 */
object SecurePrefs {

    private const val STORE = "operator_chat_secure"
    private const val PLAIN_STORE = "operator_chat_plain"
    private const val KEY_ALIAS = "operator_chat_credentials"
    private const val URL_KEY = "url"
    private const val TOKEN_KEY = "token"
    private const val IV_SUFFIX = ".iv"
    private const val LEGACY_STORE = "operator_chat"
    private var cachedKey: SecretKey? = null

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
        context.getSharedPreferences(PLAIN_STORE, Context.MODE_PRIVATE).edit().clear().apply()
        legacyPrefs(context).edit().remove(URL_KEY).remove(TOKEN_KEY).apply()
    }

    /** Move a value that was stored in the old plain store into the new one. */
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

    private fun plainPrefs(context: Context) =
        context.getSharedPreferences(PLAIN_STORE, Context.MODE_PRIVATE)

    private fun read(context: Context, key: String): String? {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val encoded = prefs.getString(key, null)
        val ivEncoded = prefs.getString(key + IV_SUFFIX, null)
        if (encoded != null && ivEncoded != null) {
            val value = try {
                decrypt(encoded, ivEncoded)
            } catch (_: Exception) {
                null
            }
            if (value != null) return value
        }
        // The fallback store (no keystore available).
        return plainPrefs(context).getString(key, null)
    }

    /**
     * Encrypt a value into the keystore-backed store.
     *
     * The keystore can fail (a locked device, a broken provider), and this
     * runs inside Application.onCreate - so a throw here would take the whole
     * app down before it even started. A failed write is a plain value, not a
     * crash.
     */
    private fun write(context: Context, key: String, value: String) {
        if (value.isEmpty()) return
        val encoded = try {
            encrypt(value)
        } catch (_: Exception) {
            null
        }
        if (encoded == null) {
            plainPrefs(context).edit().putString(key, value).apply()
            return
        }
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString(key, encoded.first)
            .putString(key + IV_SUFFIX, encoded.second)
            .apply()
    }

    private fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFor())
        val payload = Base64.encodeToString(
            cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP,
        )
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        return payload to iv
    }

    private fun decrypt(encoded: String, ivEncoded: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyFor(),
            GCMParameterSpec(128, Base64.decode(ivEncoded, Base64.NO_WRAP)),
        )
        return String(
            cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)),
            StandardCharsets.UTF_8,
        )
    }

    private fun keyFor(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(KEY_ALIAS, null)
            if (existing is SecretKey) {
                cachedKey = existing
                return existing
            }

            val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
            generator.init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            val generated = generator.generateKey()
            cachedKey = generated
            return generated
        }
    }
}
