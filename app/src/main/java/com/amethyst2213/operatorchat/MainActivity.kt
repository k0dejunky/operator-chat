package com.amethyst2213.operatorchat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var convInput: EditText
    private lateinit var modeLabel: TextView
    private lateinit var pendingLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var messagesLabel: TextView
    private lateinit var replyInput: EditText
    private lateinit var replyButton: Button
    private lateinit var connectButton: Button

    private var bridge: ChatBridge? = null
    private var pollJob: Job? = null
    private var lastMemberIds = mutableSetOf<Long>()

    private val prefs by lazy { getSharedPreferences("operator_chat", MODE_PRIVATE) }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.url_input)
        tokenInput = findViewById(R.id.token_input)
        convInput = findViewById(R.id.conv_input)
        modeLabel = findViewById(R.id.mode_label)
        pendingLabel = findViewById(R.id.pending_label)
        statusLabel = findViewById(R.id.status_label)
        messagesLabel = findViewById(R.id.messages_label)
        replyInput = findViewById(R.id.reply_input)
        replyButton = findViewById(R.id.reply_button)
        connectButton = findViewById(R.id.connect_button)

        urlInput.setText(prefs.getString("url", "https://amethyst2213.com/gallery"))
        tokenInput.setText(prefs.getString("token", ""))
        convInput.setText(prefs.getLong("conversation", 0).toString().ifEmpty { "" })

        connectButton.setOnClickListener { connect() }
        replyButton.setOnClickListener { sendReply() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    private fun connect() {
        val url = urlInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString().trim()
        if (url.isEmpty() || token.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content), "Server URL and token are required.", Snackbar.LENGTH_LONG).show()
            return
        }
        prefs.edit().putString("url", url).putString("token", token).apply()
        bridge = ChatBridge(url, token)
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        statusLabel.text = "Connected — polling…"
        pollJob = lifecycleScope.launch {
            while (true) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    statusLabel.text = "Error: ${e.message ?: "network"} — retrying…"
                }
                delay(5000)
            }
        }
    }

    private suspend fun pollOnce() {
        val b = bridge ?: return
        val cid = convInput.text.toString().trim().toLongOrNull() ?: run {
            statusLabel.text = "Enter a conversation id."
            return
        }
        prefs.edit().putLong("conversation", cid).apply()

        val cfg = b.config(cid)
        modeLabel.text = "Mode: ${cfg.aiMode} · Status: ${cfg.status}"
        pendingLabel.text = "Pending: ${cfg.pending}"

        val msgs = b.pending(cid)
        val newOnes = msgs.filter { m ->
            m.senderRole == "user" && m.id !in lastMemberIds
        }
        if (newOnes.isNotEmpty()) {
            val sb = StringBuilder(messagesLabel.text)
            newOnes.forEach { sb.append("\n\n").append("Member #").append(it.id).append(":\n").append(it.message) }
            messagesLabel.text = sb.toString()
            lastMemberIds.addAll(newOnes.map { it.id })
            statusLabel.text = "${newOnes.size} new member message(s)"
        } else {
            statusLabel.text = "No new messages"
        }
    }

    private fun sendReply() {
        val b = bridge ?: return
        val cid = convInput.text.toString().trim().toLongOrNull() ?: run {
            Snackbar.make(findViewById(android.R.id.content), "Enter a conversation id first.", Snackbar.LENGTH_LONG).show()
            return
        }
        val text = replyInput.text.toString().trim()
        if (text.isEmpty()) return
        replyInput.setText("")
        lifecycleScope.launch {
            try {
                val ok = b.reply(cid, text)
                statusLabel.text = if (ok) "Reply sent" else "Reply failed"
                if (ok) {
                    messagesLabel.append("\n\nYou:\n$text")
                }
            } catch (e: Exception) {
                statusLabel.text = "Send error: ${e.message}"
            }
        }
    }
}