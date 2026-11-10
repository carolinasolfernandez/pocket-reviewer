package dev.pocketreviewer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.platform.LocalContext
import dev.pocketreviewer.AppInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.pocketreviewer.R
import dev.pocketreviewer.api.ChatMessages
import dev.pocketreviewer.config.AppConfig
import dev.pocketreviewer.config.ConfigParsing
import dev.pocketreviewer.util.GitHubHost
import kotlinx.coroutines.launch

/** Which credentials are already stored. The values themselves are never read back into the UI. */
data class SecretsState(
    val hasGithubToken: Boolean = false,
    val hasAnthropicKey: Boolean = false,
    val hasSlackToken: Boolean = false,
)

/** Texts the app writes to chat. Blank = that message is never sent. */
data class MessageTemplates(
    val threadIssues: String = ChatMessages.DEFAULT_THREAD_MSG,
    val conflicts: String = ChatMessages.DEFAULT_CONFLICTS,
    val approveFailed: String = ChatMessages.DEFAULT_APPROVE_FAILED,
)

/** Everything the Settings screen needs from the host activity. */
interface SettingsActions {
    suspend fun loadConfig(): AppConfig
    suspend fun saveConfig(config: AppConfig)
    suspend fun loadSecrets(): SecretsState
    /** Stores [value] encrypted under [key]; null/blank removes it. */
    suspend fun saveSecret(key: String, value: String?)
    suspend fun loadMessages(): MessageTemplates
    suspend fun saveMessages(messages: MessageTemplates)

    suspend fun isClaudeConnected(): Boolean
    fun startClaudeLogin()
    suspend fun submitClaudeAuthCode(code: String): Boolean
    suspend fun disconnectClaude()

    fun googleSignIn()
    fun googleSignOut()

    /** Verifies a Slack token; returns "user @ workspace" or throws with Slack's error code. */
    suspend fun testSlackToken(token: String): String
}

@Composable
fun SettingsScreen(
    actions: SettingsActions,
    googleSignInState: GoogleSignInState,
) {
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<AppConfig?>(null) }
    var secrets by remember { mutableStateOf(SecretsState()) }
    var messages by remember { mutableStateOf(MessageTemplates()) }

    LaunchedEffect(Unit) {
        config = actions.loadConfig()
        secrets = actions.loadSecrets()
        messages = actions.loadMessages()
    }

    val current = config
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val save: (AppConfig) -> Unit = { updated ->
        config = updated
        scope.launch { actions.saveConfig(updated) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )

        GeneralCard(current, save)

        Section(stringResource(R.string.section_accounts), stringResource(R.string.section_accounts_summary)) {
            AccountsSection(actions, secrets, googleSignInState) {
                scope.launch { secrets = actions.loadSecrets() }
            }
        }
        Section(stringResource(R.string.section_sources), stringResource(R.string.section_sources_summary)) {
            SourcesSection(current, save)
        }
        Section(stringResource(R.string.section_filters), stringResource(R.string.section_filters_summary)) {
            FiltersSection(current, save)
        }
        Section(stringResource(R.string.section_review), stringResource(R.string.section_review_summary)) {
            ReviewSection(current, save)
        }
        Section(stringResource(R.string.section_messages), stringResource(R.string.section_messages_summary)) {
            MessagesSection(current, messages, save) { updated ->
                messages = updated
                scope.launch { actions.saveMessages(updated) }
            }
        }

        AboutCard()
    }
}

// ── About ────────────────────────────────────────────────────────────────────

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.about_version, stringResource(R.string.app_name), version),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(R.string.about_author, AppInfo.AUTHOR_NAME),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                AppInfo.AUTHOR_EMAIL,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${AppInfo.AUTHOR_EMAIL}"))
                        )
                    }
                }
            )
            Text(
                stringResource(R.string.about_license, AppInfo.LICENSE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Building blocks ──────────────────────────────────────────────────────────

@Composable
private fun Section(
    title: String,
    summary: String,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Help(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SubHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun SaveButton(dirty: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = dirty, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (dirty) R.string.action_save else R.string.action_saved))
    }
}

/** A list setting edited as comma-separated text. */
@Composable
private fun ListField(label: String, value: String, placeholder: String, help: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = { Text(help) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(6)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

private fun List<String>.asText() = joinToString(", ")

// ── General (saved immediately) ──────────────────────────────────────────────

@Composable
private fun GeneralCard(config: AppConfig, save: (AppConfig) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SwitchRow(
                stringResource(R.string.general_listen),
                stringResource(if (config.notificationAnalysis) R.string.general_listen_on else R.string.general_listen_off),
                config.notificationAnalysis
            ) { save(config.copy(notificationAnalysis = it)) }
            SwitchRow(
                stringResource(R.string.general_mode_autonomous),
                stringResource(if (config.autonomous) R.string.general_mode_autonomous_desc else R.string.general_mode_manual_desc),
                config.autonomous
            ) { save(config.copy(autonomous = it)) }
        }
    }
}

// ── Accounts ─────────────────────────────────────────────────────────────────

@Composable
private fun SecretField(
    label: String,
    placeholder: String,
    help: String,
    stored: Boolean,
    onSave: (String?) -> Unit,
    extraAction: (@Composable (String) -> Unit)? = null,
) {
    var value by remember { mutableStateOf("") }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        label = { Text(label) },
        placeholder = { Text(if (stored) stringResource(R.string.secret_stored_placeholder) else placeholder) },
        supportingText = { Text(if (stored) stringResource(R.string.secret_stored_help, help) else help) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { onSave(value); value = "" }, enabled = value.isNotBlank()) {
            Text(stringResource(R.string.action_save))
        }
        extraAction?.invoke(value)
        if (stored) {
            TextButton(onClick = { onSave(null); value = "" }) {
                Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ConnectedCard(title: String, subtitle: String, onDisconnect: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("✓", color = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDisconnect) {
                Text(stringResource(R.string.action_disconnect), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AccountsSection(
    actions: SettingsActions,
    secrets: SecretsState,
    googleSignInState: GoogleSignInState,
    onSecretsChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val saveSecret: (String, String?) -> Unit = { key, value ->
        scope.launch { actions.saveSecret(key, value); onSecretsChanged() }
    }

    Help(stringResource(R.string.accounts_encrypted_note))

    // GitHub
    SubHeading(stringResource(R.string.accounts_github))
    SecretField(
        label = stringResource(R.string.accounts_github_token),
        placeholder = "github_pat_… / ghp_…",
        help = stringResource(R.string.accounts_github_help),
        stored = secrets.hasGithubToken,
        onSave = { saveSecret(dev.pocketreviewer.config.Keys.GITHUB_TOKEN, it) }
    )

    Divider()

    // Claude
    SubHeading(stringResource(R.string.accounts_claude))
    SecretField(
        label = stringResource(R.string.accounts_claude_key),
        placeholder = "sk-ant-…",
        help = stringResource(R.string.accounts_claude_key_help),
        stored = secrets.hasAnthropicKey,
        onSave = { saveSecret(dev.pocketreviewer.config.Keys.ANTHROPIC_KEY, it) }
    )
    ClaudeOAuth(actions)

    Divider()

    // Google Chat
    SubHeading(stringResource(R.string.accounts_google))
    if (googleSignInState == GoogleSignInState.SIGNED_IN) {
        ConnectedCard(
            stringResource(R.string.accounts_google_connected),
            stringResource(R.string.accounts_google_connected_desc),
            actions::googleSignOut
        )
    } else {
        OutlinedButton(onClick = actions::googleSignIn, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.accounts_google_connect))
        }
        Help(stringResource(R.string.accounts_google_help))
    }

    Divider()

    // Slack
    SubHeading(stringResource(R.string.accounts_slack))
    var slackTest by remember { mutableStateOf<String?>(null) }
    val slackOk = stringResource(R.string.accounts_slack_test_ok)
    val slackFail = stringResource(R.string.accounts_slack_test_fail)
    SecretField(
        label = stringResource(R.string.accounts_slack_token),
        placeholder = "xoxp-…",
        help = stringResource(R.string.accounts_slack_help),
        stored = secrets.hasSlackToken,
        onSave = { slackTest = null; saveSecret(dev.pocketreviewer.config.Keys.SLACK_TOKEN, it) },
        extraAction = { typed ->
            OutlinedButton(
                enabled = typed.isNotBlank(),
                onClick = {
                    scope.launch {
                        slackTest = runCatching { actions.testSlackToken(typed.trim()) }
                            .fold({ "$slackOk $it" }, { "$slackFail ${it.message}" })
                    }
                }
            ) { Text(stringResource(R.string.action_test)) }
        }
    )
    slackTest?.let { Help(it) }
}

@Composable
private fun ClaudeOAuth(actions: SettingsActions) {
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(false) }
    var authCode by remember { mutableStateOf("") }
    var exchanging by remember { mutableStateOf(false) }
    var codeError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { connected = actions.isClaudeConnected() }

    if (connected) {
        ConnectedCard(
            stringResource(R.string.claude_oauth_connected),
            stringResource(R.string.claude_oauth_connected_desc)
        ) {
            scope.launch {
                try { actions.disconnectClaude() } finally { connected = actions.isClaudeConnected() }
            }
        }
        return
    }
    Text(stringResource(R.string.claude_oauth_title), style = MaterialTheme.typography.bodyMedium)
    Help(stringResource(R.string.claude_oauth_warning))
    OutlinedButton(onClick = actions::startClaudeLogin, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.claude_oauth_start))
    }
    OutlinedTextField(
        value = authCode,
        onValueChange = { authCode = it; codeError = false },
        label = { Text(stringResource(R.string.claude_oauth_code)) },
        isError = codeError,
        supportingText = { if (codeError) Text(stringResource(R.string.claude_oauth_code_error)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = {
            scope.launch {
                exchanging = true
                val ok = try { actions.submitClaudeAuthCode(authCode) } finally { exchanging = false }
                if (ok) { authCode = ""; connected = actions.isClaudeConnected() } else codeError = true
            }
        },
        enabled = authCode.isNotBlank() && !exchanging,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(if (exchanging) R.string.claude_oauth_connecting else R.string.claude_oauth_confirm))
    }
}

// ── Sources ──────────────────────────────────────────────────────────────────

@Composable
private fun SourcesSection(config: AppConfig, save: (AppConfig) -> Unit) {
    var googleChat by remember(config) { mutableStateOf(config.sourceGoogleChat) }
    var slack by remember(config) { mutableStateOf(config.sourceSlack) }
    var other by remember(config) { mutableStateOf(config.sourceOtherApps) }
    var spaces by remember(config) { mutableStateOf(config.chatSpaces.asText()) }
    var channels by remember(config) { mutableStateOf(config.slackChannels.asText()) }

    val draft = config.copy(
        sourceGoogleChat = googleChat, sourceSlack = slack, sourceOtherApps = other,
        chatSpaces = ConfigParsing.parseList(spaces), slackChannels = ConfigParsing.parseList(channels)
    )

    SwitchRow(stringResource(R.string.sources_google_chat), stringResource(R.string.sources_google_chat_desc), googleChat) { googleChat = it }
    if (googleChat) {
        ListField(
            stringResource(R.string.sources_chat_spaces), spaces, "Engineering, PR reviews",
            stringResource(R.string.sources_chat_spaces_help)
        ) { spaces = it }
    }
    Divider()
    SwitchRow(stringResource(R.string.sources_slack), stringResource(R.string.sources_slack_desc), slack) { slack = it }
    if (slack) {
        ListField(
            stringResource(R.string.sources_slack_channels), channels, "#code-review, #backend",
            stringResource(R.string.sources_slack_channels_help)
        ) { channels = it }
    }
    Divider()
    SwitchRow(stringResource(R.string.sources_other), stringResource(R.string.sources_other_desc), other) { other = it }

    SaveButton(dirty = draft != config) { save(draft) }
}

// ── Filters ──────────────────────────────────────────────────────────────────

@Composable
private fun FiltersSection(config: AppConfig, save: (AppConfig) -> Unit) {
    var owners by remember(config) { mutableStateOf(config.allowedOwners.asText()) }
    var ignored by remember(config) { mutableStateOf(config.ignoredRepos.asText()) }
    var related by remember(config) { mutableStateOf(config.relatedRepos.asText()) }
    var host by remember(config) { mutableStateOf(config.githubHost) }

    val draft = config.copy(
        githubHost = GitHubHost.normalize(host),
        allowedOwners = ConfigParsing.parseList(owners),
        ignoredRepos = ConfigParsing.parseList(ignored),
        relatedRepos = ConfigParsing.parseList(related)
    )

    OutlinedTextField(
        value = host, onValueChange = { host = it },
        label = { Text(stringResource(R.string.filters_github_host)) },
        placeholder = { Text(GitHubHost.DEFAULT) },
        supportingText = { Text(stringResource(R.string.filters_github_host_help)) },
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    ListField(
        stringResource(R.string.filters_owners), owners, "my-org, my-user",
        stringResource(R.string.filters_owners_help)
    ) { owners = it }
    ListField(
        stringResource(R.string.filters_ignored), ignored, "docs, my-org/legacy-app",
        stringResource(R.string.filters_ignored_help)
    ) { ignored = it }
    ListField(
        stringResource(R.string.filters_related), related, "backend, frontend",
        stringResource(R.string.filters_related_help)
    ) { related = it }

    SaveButton(dirty = draft != config) { save(draft) }
}

// ── Review behaviour ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewSection(config: AppConfig, save: (AppConfig) -> Unit) {
    var model by remember(config) { mutableStateOf(config.model) }
    var language by remember(config) { mutableStateOf(config.reviewLanguage) }
    var mergeMethod by remember(config) { mutableStateOf(config.mergeMethod) }
    var autoApprove by remember(config) { mutableStateOf(config.autoApprove) }
    var autoMerge by remember(config) { mutableStateOf(config.autoMerge) }
    var delayMin by remember(config) { mutableStateOf(config.delayMinSec.toString()) }
    var delayMax by remember(config) { mutableStateOf(config.delayMaxSec.toString()) }
    var ciWait by remember(config) { mutableStateOf(config.ciWaitMinutes.toString()) }
    var ignoredChecks by remember(config) { mutableStateOf(config.ignoredChecks.asText()) }
    var maxDiff by remember(config) { mutableStateOf(config.maxDiffChars.toString()) }
    var maxTokens by remember(config) { mutableStateOf(config.maxOutputTokens.toString()) }

    val (lo, hi) = ConfigParsing.delayRange(
        ConfigParsing.parseInt(delayMin, config.delayMinSec, 0, 3600),
        ConfigParsing.parseInt(delayMax, config.delayMaxSec, 0, 3600)
    )
    val draft = config.copy(
        model = model.trim().ifEmpty { AppConfig.DEFAULT_MODEL },
        reviewLanguage = language.trim().ifEmpty { AppConfig.DEFAULT_REVIEW_LANGUAGE },
        mergeMethod = mergeMethod,
        autoApprove = autoApprove,
        autoMerge = autoMerge && autoApprove,
        delayMinSec = lo, delayMaxSec = hi,
        ciWaitMinutes = ConfigParsing.parseInt(ciWait, config.ciWaitMinutes, 0, 120),
        ignoredChecks = ConfigParsing.parseList(ignoredChecks),
        maxDiffChars = ConfigParsing.parseInt(
            maxDiff, config.maxDiffChars, AppConfig.MIN_DIFF_CHARS, AppConfig.MAX_DIFF_CHARS
        ),
        maxOutputTokens = ConfigParsing.parseInt(
            maxTokens, config.maxOutputTokens, AppConfig.MIN_OUTPUT_TOKENS, AppConfig.MAX_OUTPUT_TOKENS
        )
    )

    OutlinedTextField(
        value = model, onValueChange = { model = it },
        label = { Text(stringResource(R.string.review_model)) },
        supportingText = { Text(stringResource(R.string.review_model_help, AppConfig.DEFAULT_MODEL)) },
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = language, onValueChange = { language = it },
        label = { Text(stringResource(R.string.review_language)) },
        supportingText = { Text(stringResource(R.string.review_language_help)) },
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(R.string.review_max_diff), maxDiff, Modifier.weight(1f)) { maxDiff = it }
        NumberField(stringResource(R.string.review_max_tokens), maxTokens, Modifier.weight(1f)) { maxTokens = it }
    }
    Help(stringResource(
        R.string.review_limits_help,
        AppConfig.MIN_DIFF_CHARS, AppConfig.MAX_DIFF_CHARS, AppConfig.MIN_OUTPUT_TOKENS, AppConfig.MAX_OUTPUT_TOKENS
    ))

    Divider()
    SubHeading(stringResource(R.string.review_autonomous_heading))
    SwitchRow(stringResource(R.string.review_auto_approve), stringResource(R.string.review_auto_approve_desc), autoApprove) { autoApprove = it }
    SwitchRow(stringResource(R.string.review_auto_merge), stringResource(R.string.review_auto_merge_desc), autoMerge && autoApprove) {
        autoMerge = it
        if (it) autoApprove = true
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(R.string.review_delay_min), delayMin, Modifier.weight(1f)) { delayMin = it }
        NumberField(stringResource(R.string.review_delay_max), delayMax, Modifier.weight(1f)) { delayMax = it }
    }
    Help(stringResource(R.string.review_delay_help))
    NumberField(stringResource(R.string.review_ci_wait), ciWait, Modifier.fillMaxWidth()) { ciWait = it }
    Help(stringResource(R.string.review_ci_wait_help))

    Divider()
    SubHeading(stringResource(R.string.review_merge_method))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppConfig.MERGE_METHODS.forEach { method ->
            FilterChip(selected = mergeMethod == method, onClick = { mergeMethod = method }, label = { Text(method) })
        }
    }
    ListField(
        stringResource(R.string.review_ignored_checks), ignoredChecks, "snyk, codecov",
        stringResource(R.string.review_ignored_checks_help)
    ) { ignoredChecks = it }

    SaveButton(dirty = draft != config) { save(draft) }
}

// ── Messages & reactions ─────────────────────────────────────────────────────

@Composable
private fun MessagesSection(
    config: AppConfig,
    messages: MessageTemplates,
    saveConfig: (AppConfig) -> Unit,
    saveMessages: (MessageTemplates) -> Unit,
) {
    var threadIssues by remember(messages) { mutableStateOf(messages.threadIssues) }
    var conflicts by remember(messages) { mutableStateOf(messages.conflicts) }
    var approveFailed by remember(messages) { mutableStateOf(messages.approveFailed) }
    var working by remember(config) { mutableStateOf(config.emojiWorking) }
    var approve by remember(config) { mutableStateOf(config.emojiApprove) }
    var comment by remember(config) { mutableStateOf(config.emojiComment) }

    val messagesDraft = MessageTemplates(threadIssues.trim(), conflicts.trim(), approveFailed.trim())
    val configDraft = config.copy(
        emojiWorking = working.trim().ifEmpty { AppConfig.DEFAULT_EMOJI_WORKING },
        emojiApprove = approve.trim().ifEmpty { AppConfig.DEFAULT_EMOJI_APPROVE },
        emojiComment = comment.trim().ifEmpty { AppConfig.DEFAULT_EMOJI_COMMENT }
    )

    SubHeading(stringResource(R.string.messages_heading))
    Help(stringResource(R.string.messages_blank_help))
    OutlinedTextField(
        value = threadIssues, onValueChange = { threadIssues = it },
        label = { Text(stringResource(R.string.messages_thread)) },
        supportingText = { Text(stringResource(R.string.messages_thread_help)) },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = conflicts, onValueChange = { conflicts = it },
        label = { Text(stringResource(R.string.messages_conflicts)) },
        supportingText = { Text(stringResource(R.string.messages_conflicts_help)) },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = approveFailed, onValueChange = { approveFailed = it },
        label = { Text(stringResource(R.string.messages_approve_failed)) },
        supportingText = { Text(stringResource(R.string.messages_approve_failed_help)) },
        modifier = Modifier.fillMaxWidth()
    )

    Divider()
    SubHeading(stringResource(R.string.emoji_heading))
    Help(stringResource(R.string.emoji_help))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = working, onValueChange = { working = it },
            label = { Text(stringResource(R.string.emoji_working)) },
            singleLine = true, modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = approve, onValueChange = { approve = it },
            label = { Text(stringResource(R.string.emoji_approve)) },
            singleLine = true, modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = comment, onValueChange = { comment = it },
            label = { Text(stringResource(R.string.emoji_comment)) },
            singleLine = true, modifier = Modifier.weight(1f)
        )
    }

    SaveButton(dirty = messagesDraft != messages || configDraft != config) {
        if (messagesDraft != messages) saveMessages(messagesDraft)
        if (configDraft != config) saveConfig(configDraft)
    }
}
