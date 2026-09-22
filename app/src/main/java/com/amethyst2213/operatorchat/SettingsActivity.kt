package com.amethyst2213.operatorchat

import android.app.AlertDialog
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Global operator app settings: notifications on/off, the default chat
 * notification tone, and an in-app update check.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var baseUrl: String
    private val prefs by lazy { getSharedPreferences("operator_chat", MODE_PRIVATE) }
    private lateinit var notifyStatus: TextView
    private lateinit var toneLabel: TextView
    private lateinit var biometricLabel: TextView
    private lateinit var quietLabel: TextView
    private lateinit var diagnosticsLabel: TextView
    private lateinit var updateLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle("Settings")
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        StatusBarToolbar.apply(this, toolbar)

        baseUrl = intent.getStringExtra("base_url") ?: ""

        notifyStatus = findViewById(R.id.notify_status)
        toneLabel = findViewById(R.id.tone_label)
        biometricLabel = findViewById(R.id.biometric_label)
        quietLabel = findViewById(R.id.quiet_label)
        diagnosticsLabel = findViewById(R.id.diagnostics_label)
        updateLabel = findViewById(R.id.update_label)

        findViewById<Button>(R.id.btn_notify_toggle).setOnClickListener { toggleNotifications() }
        findViewById<Button>(R.id.btn_tone).setOnClickListener { pickTone() }
        findViewById<Button>(R.id.btn_biometric).setOnClickListener { toggleBiometric() }
        findViewById<Button>(R.id.btn_quiet).setOnClickListener { editQuietHours() }
        findViewById<Button>(R.id.btn_diagnostics).setOnClickListener { pingServer() }
        findViewById<Button>(R.id.btn_check_update).setOnClickListener { checkForUpdate() }

        refresh()
        refreshDiagnostics()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        val on = prefs.getBoolean("notify_enabled", true)
        notifyStatus.text = if (on) "Notifications: ON" else "Notifications: OFF"
        val toneUri = prefs.getString("notify_tone", "") ?: ""
        toneLabel.text = "Default tone: " + (if (toneUri.isEmpty()) "Default" else readableName(toneUri))
        val bio = prefs.getBoolean("biometric_lock", true)
        biometricLabel.text = "Biometric lock: " + if (bio) "ON" else "OFF"
        val qOn = prefs.getBoolean("quiet_hours_enabled", false)
        val qStart = prefs.getString("quiet_start", "22:00") ?: "22:00"
        val qEnd = prefs.getString("quiet_end", "08:00") ?: "08:00"
        quietLabel.text = "Quiet hours: " + if (qOn) "ON ($qStart–$qEnd)" else "OFF"
    }

    private fun toggleBiometric() {
        val on = !prefs.getBoolean("biometric_lock", true)
        prefs.edit().putBoolean("biometric_lock", on).apply()
        refresh()
    }

    private fun refreshDiagnostics() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        val lastSync = prefs.getString("last_sync_at", null) ?: "never"
        val lastError = prefs.getString("last_error", null)
        diagnosticsLabel.text = buildString {
            append("App: v$versionName\n")
            append("Server: ").append(if (baseUrl.isEmpty()) "not set" else baseUrl).append("\n")
            append("Last sync: ").append(lastSync).append("\n")
            if (!lastError.isNullOrEmpty()) append("Last error: ").append(lastError).append("\n")
        }
    }

    private fun pingServer() {
        if (baseUrl.isEmpty()) {
            diagnosticsLabel.text = "Connect in the Users list first."
            return
        }
        diagnosticsLabel.text = "Pinging server…"
        val token = SecurePrefs.token(this)
        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            val v = ChatBridge(baseUrl, token).checkForUpdate()
            val ms = System.currentTimeMillis() - start
            val ok = v != null
            prefs.edit().putString("last_sync_at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
                .apply()
            if (!ok) prefs.edit().putString("last_error", "Ping failed").apply()
            refreshDiagnostics()
            diagnosticsLabel.text = (if (ok) "Server reachable — " else "Server unreachable — ") + "$ms ms"
        }
    }

    private fun editQuietHours() {
        val start = EditText(this).apply {
            hint = "Start HH:MM"
            setText(prefs.getString("quiet_start", "22:00"))
        }
        val end = EditText(this).apply {
            hint = "End HH:MM"
            setText(prefs.getString("quiet_end", "08:00"))
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(start)
        column.addView(end)
        val quietOn = prefs.getBoolean("quiet_hours_enabled", false)
        AlertDialog.Builder(this)
            .setTitle("Quiet hours")
            .setMessage("Between these times, new-message notifications are silent.")
            .setView(column)
            .setPositiveButton(if (quietOn) "Save & enable" else "Enable") { _, _ ->
                prefs.edit()
                    .putString("quiet_start", start.text.toString().trim().ifEmpty { "22:00" })
                    .putString("quiet_end", end.text.toString().trim().ifEmpty { "08:00" })
                    .putBoolean("quiet_hours_enabled", true)
                    .apply()
                refresh()
                restartPollService()
            }
            .setNeutralButton("Disable") { _, _ ->
                prefs.edit().putBoolean("quiet_hours_enabled", false).apply()
                refresh()
                restartPollService()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .show()
    }

    private fun toggleNotifications() {
        val on = !prefs.getBoolean("notify_enabled", true)
        prefs.edit().putBoolean("notify_enabled", on).apply()
        refresh()
        // Persist across the poll service by restarting it.
        restartPollService()
    }

    private fun pickTone() {
        val current = prefs.getString("notify_tone", null)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Chat notification tone")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current))
        }
        ringtoneResult.launch(intent)
    }

    private val ringtoneResult = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri != null) {
            prefs.edit().putString("notify_tone", uri.toString()).apply()
            refresh()
        }
    }

    private fun checkForUpdate() {
        if (baseUrl.isEmpty()) {
            updateLabel.text = "Connect in the Users list first."
            return
        }
        updateLabel.text = "Checking for updates…"
        val token = SecurePrefs.token(this)
        lifecycleScope.launch {
            val v = ChatBridge(baseUrl, token).checkForUpdate()
            if (v == null) {
                updateLabel.text = "Could not reach the update server."
                return@launch
            }
            val current = packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
            if (v.versionCode > current) {
                updateLabel.text = "Update available: v${v.latestVersion}\n${v.changelog}"
                Updater.downloadAndInstall(this@SettingsActivity, baseUrl, v, updateLabel)
            } else {
                updateLabel.text = "You're on the latest version (${v.latestVersion})."
            }
        }
    }

    private fun readableName(uri: String): String {
        val u = Uri.parse(uri)
        return if (u.lastPathSegment == null) "Default" else u.lastPathSegment!!.replace("_", " ").take(40)
    }

    private fun restartPollService() {
        try {
            val i = Intent(this, ChatPollService::class.java)
            stopService(i)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }
}
