package com.amethyst2213.operatorchat

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.amethyst2213.operatorchat.ui.components.StudioBottomBar
import com.amethyst2213.operatorchat.ui.components.StudioTab
import com.amethyst2213.operatorchat.ui.inbox.InboxScreen
import com.amethyst2213.operatorchat.ui.inbox.InboxViewModel
import com.amethyst2213.operatorchat.ui.live.LiveScreen
import com.amethyst2213.operatorchat.ui.live.LiveViewModel
import com.amethyst2213.operatorchat.ui.login.LockScreen
import com.amethyst2213.operatorchat.ui.login.LoginScreen
import com.amethyst2213.operatorchat.ui.schedule.ScheduleScreen
import com.amethyst2213.operatorchat.ui.schedule.ScheduleViewModel
import com.amethyst2213.operatorchat.ui.settings.SettingsScreen
import com.amethyst2213.operatorchat.ui.settings.SettingsViewModel
import com.amethyst2213.operatorchat.ui.theme.Studio
import com.amethyst2213.operatorchat.ui.theme.StudioTheme
import com.amethyst2213.operatorchat.ui.thread.ThreadScreen
import com.amethyst2213.operatorchat.ui.thread.ThreadViewModel

/**
 * v4: one activity, one Compose tree, four tabs and a conversation route.
 *
 * The old app had an activity per screen, each with its own lifecycle and its
 * own copy of the session; the operator could change the token in Settings and
 * the other screens kept the old one. Everything here is one state spine.
 */
class MainActivity : AppCompatActivity() {

    private val appVm: AppViewModel by viewModels()

    private var tab by mutableStateOf(StudioTab.Chats)
    private var threadId by mutableStateOf(0L)
    private var threadName by mutableStateOf("")
    private var threadMode by mutableStateOf("operator")
    private var threadReplies by mutableStateOf(true)
    private var askNotifications by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ enforces edge-to-edge for apps targeting 35+, so the
        // old "let the decor fit the system windows" call is ignored there
        // and the content would slide under the status and navigation bars.
        // Use the real edge-to-edge path on 15+, keep the pre-15 behaviour
        // (where the window itself insets the content) untouched.
        if (Build.VERSION.SDK_INT >= 35) {
            enableEdgeToEdge()
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.parseColor("#0B0B10")
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.parseColor("#0B0B10")
        }

        handleIntent(intent)

        setContent {
            StudioTheme {
                val state by appVm.state.collectAsStateWithLifecycle()
                val context = LocalContext.current

                LaunchedEffect(state.signedIn, state.locked) {
                    if (state.signedIn && !state.locked) {
                        try {
                            val intent = Intent(context, ChatPollService::class.java)
                            if (Build.VERSION.SDK_INT >= 26) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                        } catch (_: Exception) {
                        }
                        appVm.checkForUpdate()
                        if (Build.VERSION.SDK_INT >= 33 && !askNotifications &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            askNotifications = true
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (Build.VERSION.SDK_INT >= 35) {
                                Modifier.statusBarsPadding().navigationBarsPadding()
                            } else {
                                Modifier
                            }
                        )
                ) {
                    when {
                        !state.signedIn -> LoginScreen(
                            initialUrl = state.url,
                            initialToken = state.token,
                            busy = state.busy,
                            error = state.error,
                            update = state.update,
                            onConnect = { url, token -> appVm.connect(url, token) },
                            onInstallUpdate = { appVm.installUpdate() },
                            onDismissUpdate = { appVm.dismissUpdate() },
                        )
                        state.locked -> LockScreen(
                            error = state.error,
                            biometricAvailable = state.biometricAvailable,
                            passcodeSet = state.passcodeSet,
                            onUnlockPin = { appVm.unlockWithPin(it) },
                            onBiometric = { promptBiometric() },
                            onForgot = { appVm.signOut(wipePasscode = true) },
                        )
                        else -> StudioRoot(
                            appVm = appVm,
                            state = state,
                            tab = tab,
                            onTab = { tab = it },
                            threadId = threadId,
                            threadName = threadName,
                            threadMode = threadMode,
                            threadReplies = threadReplies,
                            onOpenThread = { id, name, mode, replies ->
                                threadId = id
                                threadName = name
                                threadMode = mode
                                threadReplies = replies
                            },
                            onCloseThread = { threadId = 0L },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val conversation = intent?.getLongExtra(EXTRA_CONVERSATION, 0L) ?: 0L
        if (conversation > 0) {
            threadId = conversation
            threadName = intent?.getStringExtra(EXTRA_CONVERSATION_NAME) ?: ""
            threadMode = intent?.getStringExtra(EXTRA_CONVERSATION_MODE) ?: "operator"
            threadReplies = true
        }
        if (intent?.getStringExtra(EXTRA_TAB) == "live") {
            tab = StudioTab.Live
        }
    }

    private fun promptBiometric() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    appVm.biometricUnlocked()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Operator Chat")
            .setSubtitle("Confirm it is you")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText("Use passcode")
            .build()
        prompt.authenticate(info)
    }

    companion object {
        const val EXTRA_CONVERSATION = "open_conversation"
        const val EXTRA_CONVERSATION_NAME = "open_conversation_name"
        const val EXTRA_CONVERSATION_MODE = "open_conversation_mode"
        const val EXTRA_TAB = "open_tab"
    }
}

@Composable
private fun StudioRoot(
    appVm: AppViewModel,
    state: AppViewModel.State,
    tab: StudioTab,
    onTab: (StudioTab) -> Unit,
    threadId: Long,
    threadName: String,
    threadMode: String,
    threadReplies: Boolean,
    onOpenThread: (Long, String, String, Boolean) -> Unit,
    onCloseThread: () -> Unit,
) {
    BackHandler(enabled = threadId > 0L) { onCloseThread() }
    BackHandler(enabled = threadId == 0L && tab != StudioTab.Chats) { onTab(StudioTab.Chats) }

    if (threadId > 0L) {
        ThreadRoute(
            appVm = appVm,
            state = state,
            conversationId = threadId,
            name = threadName,
            mode = threadMode,
            replies = threadReplies,
            onBack = onCloseThread,
        )
        return
    }

    Scaffold(
        containerColor = Studio.Ink,
        // The activity already applies the status/navigation bar padding on
        // Android 15+ (and the window does it on older versions). Letting
        // Scaffold add the system-bar insets as well would double-pad.
        contentWindowInsets = WindowInsets(0),
        bottomBar = { StudioBottomBar(tab, onTab) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                StudioTab.Chats -> InboxRoute(appVm, state, onOpenThread)
                StudioTab.Live -> LiveRoute()
                StudioTab.Schedule -> ScheduleRoute()
                StudioTab.Settings -> SettingsRoute(appVm, state)
            }
        }
    }
}

@Composable
private fun InboxRoute(
    appVm: AppViewModel,
    state: AppViewModel.State,
    onOpenThread: (Long, String, String, Boolean) -> Unit,
) {
    val vm: InboxViewModel = viewModel()
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DisposableEffect(vm, context) {
        // The background poll service broadcasts when a member writes; the
        // inbox refreshes immediately instead of waiting for the operator's
        // next pull. The intent is package-scoped and never exported.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                vm.refresh(showLoading = false)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ChatPollService.ACTION_CHAT_EVENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    InboxScreen(
        state = ui,
        update = state.update,
        onRefresh = { vm.refresh() },
        onLoadMore = { vm.loadMore() },
        onMode = { vm.setMode(it) },
        onQuery = { vm.setQuery(it) },
        onToggleFavorite = { vm.toggleFavorite(it) },
        onOpen = { c ->
            onOpenThread(
                c.id,
                c.username.ifBlank { c.userEmail },
                c.aiMode,
                c.memberReplyEnabled,
            )
        },
        onSearchUsers = { vm.searchUsers(it) },
        onStartConversation = { id ->
            vm.startConversation(id) { conversation, name ->
                onOpenThread(conversation, name, "operator", true)
            }
        },
        onInstallUpdate = { appVm.installUpdate() },
        onDismissUpdate = { appVm.dismissUpdate() },
    )
}

@Composable
private fun ThreadRoute(
    appVm: AppViewModel,
    state: AppViewModel.State,
    conversationId: Long,
    name: String,
    mode: String,
    replies: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val factory = remember(conversationId) {
        viewModelFactory {
            initializer {
                ThreadViewModel(application, conversationId, name, mode, replies)
            }
        }
    }
    val vm: ThreadViewModel = viewModel(key = "thread-$conversationId", factory = factory)
    val ui by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(vm) {
        vm.start()
        onDispose { vm.stop() }
    }
    ThreadScreen(
        vm = vm,
        state = ui,
        onBack = onBack,
        onOpenMedia = { path ->
            context.startActivity(
                Intent(context, MediaViewerActivity::class.java).putExtra("path", path),
            )
        },
    )
}

@Composable
private fun LiveRoute() {
    val vm: LiveViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LiveScreen(vm, ui)
}

@Composable
private fun ScheduleRoute() {
    val vm: ScheduleViewModel = viewModel()
    val ui by vm.state.collectAsStateWithLifecycle()
    ScheduleScreen(
        state = ui,
        onRefresh = { vm.load() },
        onSchedule = { title, start, duration, description -> vm.schedule(title, start, duration, description) },
        onCancel = { vm.cancel(it) },
        onClearError = { vm.clearError() },
    )
}

@Composable
private fun SettingsRoute(appVm: AppViewModel, state: AppViewModel.State) {
    val vm: SettingsViewModel = viewModel()
    val ui by vm.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = ui,
        update = state.update,
        lockEnabled = state.lockEnabled,
        biometricAvailable = state.biometricAvailable,
        onUrl = { vm.setUrl(it) },
        onToken = { vm.setToken(it) },
        onSave = {
            vm.saveConnection {
                appVm.reloadFromPrefs()
            }
        },
        onTest = { vm.testConnection() },
        onNotifications = { vm.setNotifications(it) },
        onQuiet = { vm.setQuiet(it) },
        onQuietStart = { vm.setQuietStart(it) },
        onQuietEnd = { vm.setQuietEnd(it) },
        onTone = { vm.setTone(it) },
        onLockChanged = { appVm.changeLock(it) },
        onSetPasscode = { pin ->
            val ok = vm.setPasscode(pin)
            appVm.refreshSecurityFlags()
            ok
        },
        onRemovePasscode = {
            vm.removePasscode()
            appVm.refreshSecurityFlags()
        },
        onCheckUpdate = { appVm.checkForUpdate(force = true) },
        onInstallUpdate = { appVm.installUpdate() },
        onDismissUpdate = { appVm.dismissUpdate() },
        onDisconnect = {
            vm.disconnect {
                appVm.signOut(wipePasscode = false)
            }
        },
        onClearStatus = { vm.clearStatus() },
    )
}
