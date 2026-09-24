package com.amethyst2213.operatorchat

import android.content.Context
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Numeric passcode fallback for the biometric lock. Used when the device has
 * no biometrics enrolled (or the user opts out): the operator inbox stays
 * gated instead of silently unlocking. Only a salted PBKDF2-SHA256 hash is
 * stored (high iteration count, so a 4-digit PIN can't be brute-forced from
 * extracted prefs).
 */
object Passcode {

    private const val PREFS = "operator_chat"
    private const val KEY_HASH = "passcode_hash"
    private const val KEY_SALT = "passcode_salt"
    private const val KEY_ITERATIONS = "passcode_iterations"
    private const val DEFAULT_ITERATIONS = 120_000

    fun isSet(context: Context): Boolean =
        prefs(context).getString(KEY_HASH, null)?.isNotEmpty() == true

    fun set(context: Context, pin: String): Boolean {
        val clean = normalize(pin) ?: return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hash = hash(saltHex, clean, DEFAULT_ITERATIONS)
        prefs(context).edit()
            .putString(KEY_SALT, saltHex)
            .putString(KEY_HASH, hash)
            .putInt(KEY_ITERATIONS, DEFAULT_ITERATIONS)
            .apply()
        return true
    }

    fun verify(context: Context, pin: String): Boolean {
        val clean = normalize(pin) ?: return false
        val salt = prefs(context).getString(KEY_SALT, null) ?: return false
        val expected = prefs(context).getString(KEY_HASH, null) ?: return false
        val iterations = prefs(context).getInt(KEY_ITERATIONS, DEFAULT_ITERATIONS)
        val actual = hash(salt, clean, iterations)
        return constantTimeEquals(actual, expected)
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_SALT).remove(KEY_HASH).remove(KEY_ITERATIONS).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Accept 4-32 digits only. */
    private fun normalize(pin: String): String? {
        val clean = pin.trim()
        return if (clean.length in 4..32 && clean.all { it.isDigit() }) clean else null
    }

    private fun hash(saltHex: String, pin: String, iterations: Int): String {
        val spec = PBEKeySpec(pin.toCharArray(), hexToBytes(saltHex), iterations, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return key.encoded.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}