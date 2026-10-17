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
    private const val KEY_FAILS = "passcode_fails"
    private const val KEY_LOCK_UNTIL = "passcode_lock_until"
    private const val DEFAULT_ITERATIONS = 120_000

    /** Wrong entries before the first lockout kicks in. */
    private const val MAX_FAILS = 5

    /** First lockout window; doubles with every further failure. */
    private const val BASE_LOCK_MS = 30_000L

    /** Cap so a fat-fingered operator is never locked out for hours. */
    private const val MAX_LOCK_MS = 15 * 60_000L

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
            .remove(KEY_FAILS)
            .remove(KEY_LOCK_UNTIL)
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

    /** Milliseconds until the throttle allows another attempt (0 = not locked). */
    fun lockedForMs(context: Context): Long {
        val until = prefs(context).getLong(KEY_LOCK_UNTIL, 0L)
        return maxOf(0L, until - System.currentTimeMillis())
    }

    /**
     * Verify with throttling. A wrong entry counts towards the lockout; a
     * correct one clears the counter. Returns false while locked out even for
     * the right passcode, so a brute-forcer gains nothing by retrying fast.
     */
    fun verifyThrottled(context: Context, pin: String): Boolean {
        if (lockedForMs(context) > 0) return false

        val ok = verify(context, pin)
        val p = prefs(context)
        if (ok) {
            p.edit().remove(KEY_FAILS).remove(KEY_LOCK_UNTIL).apply()
            return true
        }

        val fails = p.getInt(KEY_FAILS, 0) + 1
        val edit = p.edit().putInt(KEY_FAILS, fails)
        if (fails >= MAX_FAILS) {
            val over = minOf(fails - MAX_FAILS, 10)
            edit.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + minOf(BASE_LOCK_MS shl over, MAX_LOCK_MS))
        }
        edit.apply()
        return false
    }

    /** Remaining lockout, in whole seconds, for a message. 0 = not locked. */
    fun lockedForSeconds(context: Context): Long = (lockedForMs(context) + 999) / 1000

    /** Clears the failure counter and any active lockout (not the passcode). */
    fun resetThrottle(context: Context) {
        prefs(context).edit().remove(KEY_FAILS).remove(KEY_LOCK_UNTIL).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_SALT).remove(KEY_HASH).remove(KEY_ITERATIONS)
            .remove(KEY_FAILS).remove(KEY_LOCK_UNTIL)
            .apply()
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