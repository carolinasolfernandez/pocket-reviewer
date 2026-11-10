package dev.pocketreviewer.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.common.api.ApiException
import dev.pocketreviewer.R
import dev.pocketreviewer.api.AnthropicAuth
import dev.pocketreviewer.api.ChatMessages
import dev.pocketreviewer.api.GoogleChatClient
import dev.pocketreviewer.api.ReviewManager
import dev.pocketreviewer.api.dataStore
import dev.pocketreviewer.config.AppConfig
import dev.pocketreviewer.config.ConfigStore
import dev.pocketreviewer.config.Keys
import dev.pocketreviewer.config.SecretStore
import dev.pocketreviewer.data.ReviewEntry
import dev.pocketreviewer.data.ReviewHistoryRepository
import dev.pocketreviewer.messaging.SlackClient
import dev.pocketreviewer.service.ManualFlow
import dev.pocketreviewer.service.ManualStepService
import dev.pocketreviewer.service.PRNotificationListener
import dev.pocketreviewer.util.GitHubHost
import dev.pocketreviewer.util.PullRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

enum class GoogleSignInState { UNKNOWN, SIGNED_IN, SIGNED_OUT }

class MainActivity : ComponentActivity(), SettingsActions {

    private lateinit var googleSignInClient: GoogleSignInClient
    private var googleSignInState by mutableStateOf(GoogleSignInState.UNKNOWN)
    private var notificationPermission by mutableStateOf(false)
    private var batteryUnrestricted by mutableStateOf(false)
    private var openPrKey by mutableStateOf<String?>(null)

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            task.getResult(ApiException::class.java)
            googleSignInState = GoogleSignInState.SIGNED_IN
        } catch (e: ApiException) {
            // statusCode 10 (DEVELOPER_ERROR) means the OAuth client for this package name
            // and signing certificate is not set up in Google Cloud — see docs/GOOGLE_CHAT.md.
            android.util.Log.e("GoogleSignIn", "Sign-in failed: statusCode=${e.statusCode} message=${e.message}")
            googleSignInState = GoogleSignInState.SIGNED_OUT
        }
    }

    // Android 13+ requires the POST_NOTIFICATIONS runtime permission. Without it,
    // every NotificationManager.notify() is silently dropped — which makes the manual
    // mode prompts and the autonomous status/result notifications invisible.
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result reflected on the next post; nothing else to do here */ }

    private fun ensurePostNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        googleSignInClient = GoogleSignIn.getClient(this, GoogleChatClient.buildSignInOptions())
        googleSignInState = if (GoogleChatClient.hasRequiredScopes(this))
            GoogleSignInState.SIGNED_IN else GoogleSignInState.SIGNED_OUT
        notificationPermission = hasNotificationPermission()
        batteryUnrestricted = isBatteryUnrestricted()
        ensurePostNotificationsPermission()

        val openTab = intent?.getStringExtra("open_tab")
        openPrKey = intent?.getStringExtra(PRNotificationListener.EXTRA_OPEN_PR_KEY)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppTabs(
                    initialTab = if (openTab == "history") TAB_HISTORY else TAB_REVIEW,
                    settingsActions = this,
                    googleSignInState = googleSignInState,
                    hasNotificationAccess = notificationPermission,
                    batteryUnrestricted = batteryUnrestricted,
                    onRequestNotificationAccess = { openNotificationSettings() },
                    onRequestBatteryExemption = { requestBatteryExemption() },
                    loadSetup = { loadSetup() },
                    testConnections = { testConnections() },
                    loadHistory = { ReviewHistoryRepository.load(this) },
                    onClearHistory = { ReviewHistoryRepository.clear(this) },
                    runAnalysis = { pr -> ReviewManager.analyzeOnly(this, pr) },
                    onPostToGitHub = { pr, text -> ReviewManager.postAnalysis(this, pr, text) },
                    onApprove = { pr -> ReviewManager.approvePR(this, pr) },
                    onApproveAndMerge = { pr -> ReviewManager.approveAndMergePR(this, pr) },
                    openPrKey = openPrKey,
                    onConsumedOpenPrKey = { openPrKey = null },
                    onStartManualStep = { prKey, prUrl, stage, action ->
                        ManualStepService.start(this, prKey, prUrl, stage, action)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationPermission = hasNotificationPermission()
        batteryUnrestricted = isBatteryUnrestricted()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openPrKey = intent.getStringExtra(PRNotificationListener.EXTRA_OPEN_PR_KEY)
    }

    // ── SettingsActions ──────────────────────────────────────────────────────

    override suspend fun loadConfig(): AppConfig = ConfigStore.load(this)

    override suspend fun saveConfig(config: AppConfig) = ConfigStore.save(this, config)

    override suspend fun loadSecrets(): SecretsState {
        val prefs = dataStore.data.first()
        return SecretsState(
            hasGithubToken = SecretStore.read(prefs, Keys.GITHUB_TOKEN) != null,
            hasAnthropicKey = SecretStore.read(prefs, Keys.ANTHROPIC_KEY) != null,
            hasSlackToken = SecretStore.read(prefs, Keys.SLACK_TOKEN) != null,
        )
    }

    override suspend fun saveSecret(key: String, value: String?) = SecretStore.put(this, key, value)

    override suspend fun loadMessages(): MessageTemplates {
        // Key never saved → default text; saved blank → empty field ("don't send").
        val prefs = dataStore.data.first()
        return MessageTemplates(
            threadIssues = prefs[stringPreferencesKey(ChatMessages.KEY_THREAD_MSG)] ?: ChatMessages.DEFAULT_THREAD_MSG,
            conflicts = prefs[stringPreferencesKey(ChatMessages.KEY_CONFLICTS)] ?: ChatMessages.DEFAULT_CONFLICTS,
            approveFailed = prefs[stringPreferencesKey(ChatMessages.KEY_APPROVE_FAILED)] ?: ChatMessages.DEFAULT_APPROVE_FAILED,
        )
    }

    override suspend fun saveMessages(messages: MessageTemplates) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(ChatMessages.KEY_THREAD_MSG)] = messages.threadIssues
            prefs[stringPreferencesKey(ChatMessages.KEY_CONFLICTS)] = messages.conflicts
            prefs[stringPreferencesKey(ChatMessages.KEY_APPROVE_FAILED)] = messages.approveFailed
        }
    }

    override suspend fun isClaudeConnected(): Boolean = AnthropicAuth.isOAuthConnected(this)

    override fun startClaudeLogin() = startClaudeOAuth()

    override suspend fun disconnectClaude() = AnthropicAuth.clearOAuth(this)

    override fun googleSignIn() = signInLauncher.launch(googleSignInClient.signInIntent)

    override fun googleSignOut() {
        googleSignInClient.signOut()
        googleSignInState = GoogleSignInState.SIGNED_OUT
    }

    override suspend fun testSlackToken(token: String): String = SlackClient.testToken(token)

    private fun startClaudeOAuth() {
        lifecycleScope.launch {
            val pkce = AnthropicAuth.generatePkce()
            val state = AnthropicAuth.generateState()
            dataStore.edit { p ->
                p[stringPreferencesKey("oauth_pkce_verifier")] = pkce.verifier
                p[stringPreferencesKey("oauth_pkce_state")] = state
            }
            val url = AnthropicAuth.buildAuthorizeUrl(pkce.challenge, state)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    override suspend fun submitClaudeAuthCode(code: String): Boolean = withContext(Dispatchers.IO) {
        val parsed = AnthropicAuth.parseAuthCode(code) ?: return@withContext false
        val prefs = dataStore.data.first()
        val verifier = prefs[stringPreferencesKey("oauth_pkce_verifier")] ?: return@withContext false
        val storedState = prefs[stringPreferencesKey("oauth_pkce_state")] ?: ""
        val (authCode, returnedState) = parsed
        if (returnedState.isNotEmpty() && storedState.isNotEmpty() && returnedState != storedState) {
            return@withContext false
        }
        try {
            val tr = AnthropicAuth.exchangeCode(authCode, returnedState.ifEmpty { storedState }, verifier)
            AnthropicAuth.storeOAuth(this@MainActivity, tr)
            dataStore.edit { p ->
                p.remove(stringPreferencesKey("oauth_pkce_verifier"))
                p.remove(stringPreferencesKey("oauth_pkce_state"))
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("AnthropicAuth", "code exchange failed: ${e.message}")
            false
        }
    }

    // ── Status ───────────────────────────────────────────────────────────────

    private suspend fun loadSetup(): SetupState {
        val prefs = dataStore.data.first()
        val config = ConfigStore.fromPrefs(prefs)
        return SetupState(
            notificationAccess = hasNotificationPermission(),
            githubToken = SecretStore.read(prefs, Keys.GITHUB_TOKEN) != null,
            claude = SecretStore.read(prefs, Keys.ANTHROPIC_KEY) != null ||
                SecretStore.read(prefs, Keys.ANTHROPIC_OAUTH_ACCESS) != null,
            googleChatEnabled = config.sourceGoogleChat,
            googleConnected = GoogleChatClient.hasRequiredScopes(this),
            slackEnabled = config.sourceSlack,
            slackConnected = SecretStore.read(prefs, Keys.SLACK_TOKEN) != null,
            listening = config.notificationAnalysis,
            autonomous = config.autonomous,
            model = config.model,
        )
    }

    private suspend fun testConnections(): ConnectionStatus = withContext(Dispatchers.IO) {
        val githubToken = SecretStore.get(this@MainActivity, Keys.GITHUB_TOKEN)
        val config = ConfigStore.load(this@MainActivity)
        val model = config.model
        val http = okhttp3.OkHttpClient()
        var error: String? = null

        val githubOk: Boolean? = githubToken?.let { token ->
            runCatching {
                http.newCall(
                    okhttp3.Request.Builder()
                        .url("${GitHubHost.apiBase(config.githubHost)}/user")
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                ).execute().use { it.isSuccessful }
            }.getOrElse { error = it.message; false }
        }

        // One-token request against the configured model: validates both the credential
        // and that the model id exists for this account.
        val anthropicOk: Boolean = runCatching {
            val body = """{"model":"$model","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
            val builder = okhttp3.Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
            AnthropicAuth.applyTo(this@MainActivity, builder)
            http.newCall(builder.build()).execute().use { res ->
                if (!res.isSuccessful) error = "Claude ${res.code}: ${res.body?.string()?.take(160)}"
                res.isSuccessful
            }
        }.getOrElse { error = error ?: it.message; false }

        ConnectionStatus(
            githubOk = githubOk,
            anthropicOk = anthropicOk,
            googleOk = GoogleChatClient.hasRequiredScopes(this@MainActivity),
            slackOk = SlackClient.testConnection(this@MainActivity),
            error = error
        )
    }

    private fun hasNotificationPermission(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(packageName)
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** True if the app is exempt from battery optimization (Doze allowlist). */
    private fun isBatteryUnrestricted(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** Opens the direct battery-exemption dialog; falls back to the list if unavailable. */
    private fun requestBatteryExemption() {
        runCatching {
            startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            ))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}

const val TAB_REVIEW = 0
const val TAB_HISTORY = 1
const val TAB_STATUS = 2
const val TAB_SETTINGS = 3
const val TAB_KB = 4

@Composable
fun AppTabs(
    initialTab: Int = TAB_REVIEW,
    settingsActions: SettingsActions,
    googleSignInState: GoogleSignInState,
    hasNotificationAccess: Boolean,
    batteryUnrestricted: Boolean,
    onRequestNotificationAccess: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    loadSetup: suspend () -> SetupState,
    testConnections: suspend () -> ConnectionStatus,
    loadHistory: () -> List<ReviewEntry>,
    onClearHistory: () -> Unit,
    runAnalysis: suspend (PullRequest) -> ReviewManager.ReviewResult,
    onPostToGitHub: suspend (PullRequest, String) -> Unit,
    onApprove: suspend (PullRequest) -> Unit,
    onApproveAndMerge: suspend (PullRequest) -> Unit,
    openPrKey: String? = null,
    onConsumedOpenPrKey: () -> Unit = {},
    onStartManualStep: (String, String, ManualFlow.Stage, String) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(initialTab) }
    var history by remember { mutableStateOf<List<ReviewEntry>>(emptyList()) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == TAB_HISTORY) history = loadHistory()
    }

    // Auto-refresh: the repository emits whenever an entry is added or changes.
    LaunchedEffect(Unit) {
        ReviewHistoryRepository.changes.collect {
            if (selectedTab == TAB_HISTORY) history = loadHistory()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == TAB_REVIEW, onClick = { selectedTab = TAB_REVIEW },
                    icon = { Icon(Icons.Default.Search, null) }, label = { Text(stringResource(R.string.tab_review)) })
                NavigationBarItem(selected = selectedTab == TAB_HISTORY, onClick = { selectedTab = TAB_HISTORY; history = loadHistory() },
                    icon = { Icon(Icons.Default.History, null) }, label = { Text(stringResource(R.string.tab_history)) })
                NavigationBarItem(selected = selectedTab == TAB_STATUS, onClick = { selectedTab = TAB_STATUS },
                    icon = { Icon(Icons.Default.MonitorHeart, null) }, label = { Text(stringResource(R.string.tab_status)) })
                NavigationBarItem(selected = selectedTab == TAB_SETTINGS, onClick = { selectedTab = TAB_SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text(stringResource(R.string.tab_settings)) })
                NavigationBarItem(selected = selectedTab == TAB_KB, onClick = { selectedTab = TAB_KB },
                    icon = { Icon(Icons.Default.Book, null) }, label = { Text(stringResource(R.string.tab_kb)) })
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                TAB_REVIEW -> ReviewScreen(runAnalysis = runAnalysis, onPostToGitHub = onPostToGitHub,
                    onApprove = onApprove, onApproveAndMerge = onApproveAndMerge)
                TAB_HISTORY -> HistoryScreen(entries = history,
                    onClear = { onClearHistory(); history = emptyList() },
                    onReReview = runAnalysis, onPostToGitHub = onPostToGitHub,
                    onApprove = onApprove, onApproveAndMerge = onApproveAndMerge,
                    openPrKey = openPrKey,
                    onConsumedOpenPrKey = onConsumedOpenPrKey,
                    onStartManualStep = onStartManualStep)
                TAB_STATUS -> StatusScreen(
                    hasNotificationAccess = hasNotificationAccess,
                    batteryUnrestricted = batteryUnrestricted,
                    onRequestNotificationAccess = onRequestNotificationAccess,
                    onRequestBatteryExemption = onRequestBatteryExemption,
                    onOpenSettings = { selectedTab = TAB_SETTINGS },
                    loadSetup = loadSetup,
                    testConnections = testConnections)
                TAB_SETTINGS -> SettingsScreen(actions = settingsActions, googleSignInState = googleSignInState)
                TAB_KB -> KnowledgeBaseScreen()
            }
        }
    }
}
