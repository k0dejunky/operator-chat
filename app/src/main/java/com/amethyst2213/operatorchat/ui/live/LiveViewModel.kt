package com.amethyst2213.operatorchat.ui.live

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amethyst2213.operatorchat.LiveApi
import com.amethyst2213.operatorchat.SecurePrefs
import com.amethyst2213.operatorchat.live.BroadcastSession
import com.amethyst2213.operatorchat.live.BroadcastState
import com.amethyst2213.operatorchat.live.LiveBroadcastService
import com.amethyst2213.operatorchat.live.LiveChatMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The live console: owns the server half of a broadcast (start/stop, pause,
 * stats, chat) and the encoder session inside the foreground service.
 *
 * The preview Surface is owned by the screen and handed in; the service owns
 * the camera, encoder and wake lock so the broadcast survives the screen.
 */
class LiveViewModel(app: Application) : AndroidViewModel(app) {

    data class Ui(
        val state: BroadcastState = BroadcastState.Idle,
        val note: String? = null,
        val startError: String? = null,
        val chatError: String? = null,
        val chat: List<LiveChatMessage> = emptyList(),
        val viewers: Int = 0,
        val since: String = "",
        val paused: Boolean = false,
        val micMuted: Boolean = false,
        val recording: Boolean = false,
        val recordPath: String? = null,
        val sessionReady: Boolean = false,
        val serviceAvailable: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var service: LiveBroadcastService? = null
    private var session: BroadcastSession? = null
    private var streamKey: String? = null
    private var prepared = false
    private var chatVisible = false
    private var stateJob: Job? = null
    private var chatJob: Job? = null
    private var statsJob: Job? = null
    private var lastChatId = 0L
    private var lastNote: String? = null

    private val base: String
        get() = SecurePrefs.url(getApplication()).trim().trimEnd('/')
    private val token: String
        get() = SecurePrefs.token(getApplication()).trim()

    private fun ensureService() {
        val app = getApplication<Application>()
        if (LiveBroadcastService.current() == null) {
            LiveBroadcastService.start(app)
        }
        viewModelScope.launch {
            val svc = LiveBroadcastService.awaitInstance() ?: run {
                _ui.value = _ui.value.copy(
                    startError = "The broadcast service did not start.",
                    serviceAvailable = false,
                )
                return@launch
            }
            service = svc
            stateJob?.cancel()
            stateJob = viewModelScope.launch {
                svc.state.collect { state -> onState(state) }
            }
            session = svc.session
            _ui.value = _ui.value.copy(
                sessionReady = session != null,
                serviceAvailable = true,
                state = svc.state.value,
            )
            svc.setChatVisible(chatVisible)
        }
    }

    private val listener = object : BroadcastSession.Listener {
        override fun onState(state: BroadcastState) {
            // Qualify it: inside this object an unqualified call resolves to
            // the override itself and recurses until the stack dies.
            this@LiveViewModel.onState(state)
        }

        override fun onLog(message: String) {
            // Progress notes are not errors; only show a new one.
            if (message != lastNote) {
                lastNote = message
                _ui.value = _ui.value.copy(note = message)
            }
        }
    }

    private fun onState(state: BroadcastState) {
        val ui = _ui.value
        when (state) {
            is BroadcastState.Live -> {
                service?.acquireWakeLock()
                _ui.value = ui.copy(
                    state = state,
                    note = null,
                    startError = null,
                    viewers = if (state.viewers > 0) state.viewers else ui.viewers,
                    since = state.since.ifBlank { ui.since },
                    paused = state.paused || ui.paused,
                )
                startLoops()
            }
            is BroadcastState.Failed -> {
                _ui.value = ui.copy(state = state, note = null)
                stopLoops()
                finishServerSession()
                // The encoder is unusable; a retry builds a fresh session.
                session = null
                prepared = false
                service = null
                LiveBroadcastService.stop(getApplication())
            }
            is BroadcastState.Stopped -> {
                _ui.value = ui.copy(state = state, note = null)
                stopLoops()
                finishServerSession()
                session = null
                prepared = false
                service = null
                LiveBroadcastService.stop(getApplication())
            }
            else -> {
                _ui.value = ui.copy(state = state)
            }
        }
    }

    /** The operator has just granted camera/mic (or the screen opened). */
    fun permissionsChanged(camera: Boolean) {
        if (camera) service?.promoteWithCamera()
    }

    /**
     * The preview surface.
     *
     * The TextureView is created by the screen long before the camera exists
     * (the camera is only opened when the operator presses Go live), so the
     * surface is kept here until there is a session to attach it to.
     */
    private var pendingPreview: android.graphics.SurfaceTexture? = null
    private var pendingWidth = 0
    private var pendingHeight = 0

    fun attachPreview(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
        pendingPreview = texture
        pendingWidth = width
        pendingHeight = height
        session?.attachPreview(texture, width, height)
    }

    fun detachPreview() {
        pendingPreview = null
        session?.detachPreview()
    }

    fun setChatVisible(visible: Boolean) {
        chatVisible = visible
        service?.setChatVisible(visible)
    }

    /**
     * The service and the encoder session, created on demand.
     *
     * Stopping a broadcast ends the service (that releases the wake lock and
     * the camera), so a retry has to build a fresh one.
     */
    private suspend fun ensureSession(): BroadcastSession? {
        if (session != null) return session
        val app = getApplication<Application>()
        if (LiveBroadcastService.current() == null) LiveBroadcastService.start(app)
        val svc = LiveBroadcastService.awaitInstance() ?: return null
        service = svc
        stateJob?.cancel()
        stateJob = viewModelScope.launch { svc.state.collect { state -> onState(state) } }
        val created = svc.session ?: svc.createSession(listener)
        session = created
        prepared = false
        pendingPreview?.let { created.attachPreview(it, pendingWidth, pendingHeight) }
        _ui.value = _ui.value.copy(sessionReady = true, serviceAvailable = true)
        return created
    }

    /** Go live: start the server session, then the RTMP publish. */
    fun goLive() {
        if (base.isEmpty() || token.isEmpty()) {
            _ui.value = _ui.value.copy(startError = "Sign in first.")
            return
        }
        if (_ui.value.state is BroadcastState.Preparing || _ui.value.state is BroadcastState.Connecting) return
        _ui.value = _ui.value.copy(startError = null, note = "Starting…", state = BroadcastState.Preparing)
        viewModelScope.launch {
            val s = ensureSession() ?: run {
                _ui.value = _ui.value.copy(
                    state = BroadcastState.Failed("The broadcast service did not start."),
                    startError = "The broadcast service did not start.",
                )
                return@launch
            }
            service?.promoteWithCamera()
            if (!prepared) {
                prepared = true
                try {
                    val landscape = getApplication<Application>().resources.configuration.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    withContext(Dispatchers.Default) { s.prepare(landscape) }
                } catch (e: Exception) {
                    _ui.value = _ui.value.copy(
                        state = BroadcastState.Failed("The camera could not start."),
                        startError = "The camera could not start: ${e.message}",
                    )
                    return@launch
                }
            }
            val started = LiveApi.start(base, token)
            if (started is LiveApi.Failure) {
                _ui.value = _ui.value.copy(
                    state = BroadcastState.Failed(started.message),
                    startError = started.message,
                    note = null,
                )
                return@launch
            }
            started as LiveApi.Started
            streamKey = started.streamKey
            service?.acquireWakeLock()
            try {
                s.start(started.rtmpUrl)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    state = BroadcastState.Failed("The camera could not start the stream."),
                    startError = "The stream could not start: ${e.message}",
                )
                return@launch
            }
            startLoops()
        }
    }

    /** Stop the broadcast and tell the server to finalise the recording. */
    fun stop() {
        val s = session
        val key = streamKey
        s?.stopStreaming()
        streamKey = null
        stopLoops()
        viewModelScope.launch(Dispatchers.IO) {
            if (key != null) LiveApi.stop(base, token, key)
        }
        _ui.value = _ui.value.copy(
            state = BroadcastState.Idle,
            paused = false,
            recording = false,
            viewers = 0,
            since = "",
        )
        session = null
        prepared = false
        service = null
        LiveBroadcastService.stop(getApplication())
    }

    fun toggleMute() {
        val muted = !_ui.value.micMuted
        session?.setMicMuted(muted)
        _ui.value = _ui.value.copy(micMuted = muted)
    }

    fun togglePause() {
        val paused = !_ui.value.paused
        session?.setPaused(paused)
        _ui.value = _ui.value.copy(paused = paused)
        viewModelScope.launch(Dispatchers.IO) { LiveApi.setPaused(base, token, paused) }
    }

    fun flipCamera() {
        session?.flipCamera()
    }

    fun toggleRecording() {
        val s = session ?: return
        if (_ui.value.recording) {
            s.stopRecording()
            _ui.value = _ui.value.copy(recording = false)
            return
        }
        val dir = getApplication<Application>().getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            ?: getApplication<Application>().filesDir
        val name = "live-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date()) + ".mp4"
        val file = s.startRecording(dir, name)
        if (file != null) {
            _ui.value = _ui.value.copy(recording = true, recordPath = file.absolutePath)
        } else {
            _ui.value = _ui.value.copy(startError = "The local recording could not start.")
        }
    }

    fun sendChat(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val ok = LiveApi.sendChat(base, token, text.trim())
            if (!ok) {
                _ui.value = _ui.value.copy(chatError = "The message could not be sent — is the stream live?")
            } else {
                _ui.value = _ui.value.copy(chatError = null)
                pollChatOnce()
            }
        }
    }

    private fun startLoops() {
        if (chatJob?.isActive != true) {
            chatJob = viewModelScope.launch {
                while (true) {
                    pollChatOnce()
                    delay(1000)
                }
            }
        }
        if (statsJob?.isActive != true) {
            statsJob = viewModelScope.launch {
                while (true) {
                    val status = withContext(Dispatchers.IO) { LiveApi.status(base, token) }
                    if (status != null) {
                        _ui.value = _ui.value.copy(viewers = status.first, since = status.second)
                    }
                    delay(5000)
                }
            }
        }
    }

    private fun stopLoops() {
        chatJob?.cancel()
        chatJob = null
        statsJob?.cancel()
        statsJob = null
    }

    /**
     * One immediate poll. The SSE endpoint holds a ~5 s window, and with the
     * pause between windows a member message took up to ~9 s to appear; the
     * JSON poll returns at once and the loop runs every second.
     */
    private suspend fun pollChatOnce() {
        val poll = LiveApi.chatPoll(base, token, lastChatId) ?: return
        val seen = _ui.value.chat.map { it.id }.toSet()
        val added = poll.messages
            .filter { it.id > lastChatId && !seen.contains(it.id) }
            .sortedBy { it.id }
        lastChatId = maxOf(lastChatId, poll.latestId, poll.messages.maxOfOrNull { it.id } ?: 0L)
        if (added.isNotEmpty()) {
            val merged = (_ui.value.chat + added).distinctBy { it.id }.takeLast(150)
            _ui.value = _ui.value.copy(chat = merged, chatError = null)
        }
    }

    /** End the server session after a failure/stop; keep the UI truthful. */
    private fun finishServerSession() {
        val key = streamKey ?: return
        streamKey = null
        viewModelScope.launch(Dispatchers.IO) { LiveApi.stop(base, token, key) }
    }

    /** Called when the screen leaves: keep broadcasting, release the preview. */
    fun onScreenLeft() {
        setChatVisible(false)
        detachPreview()
        if (!_ui.value.state.isLive && _ui.value.state !is BroadcastState.Connecting) {
            session?.release()
            session = null
            prepared = false
            LiveBroadcastService.stop(getApplication())
        }
    }

    override fun onCleared() {
        stopLoops()
        stateJob?.cancel()
        // A live broadcast belongs to the service; do not tear it down.
        if (!_ui.value.state.isLive) {
            session?.release()
            LiveBroadcastService.stop(getApplication())
        }
        super.onCleared()
    }
}
