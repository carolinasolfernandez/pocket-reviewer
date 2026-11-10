package dev.pocketreviewer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.pocketreviewer.R
import dev.pocketreviewer.service.PRNotificationListener
import kotlinx.coroutines.launch

data class ConnectionStatus(
    val githubOk: Boolean? = null,
    val anthropicOk: Boolean? = null,
    val googleOk: Boolean? = null,
    /** null = no Slack token stored. */
    val slackOk: Boolean? = null,
    val error: String? = null
)

/** What is (not) configured yet — drives the setup checklist. */
data class SetupState(
    val notificationAccess: Boolean = false,
    val githubToken: Boolean = false,
    val claude: Boolean = false,
    val googleChatEnabled: Boolean = true,
    val googleConnected: Boolean = false,
    val slackEnabled: Boolean = true,
    val slackConnected: Boolean = false,
    val listening: Boolean = true,
    val autonomous: Boolean = true,
    val model: String = "",
) {
    /** The app can detect and review PRs once these three are in place. */
    val ready: Boolean get() = notificationAccess && githubToken && claude
}

@Composable
fun StatusScreen(
    hasNotificationAccess: Boolean,
    batteryUnrestricted: Boolean,
    onRequestNotificationAccess: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenSettings: () -> Unit,
    loadSetup: suspend () -> SetupState,
    testConnections: suspend () -> ConnectionStatus,
) {
    val scope = rememberCoroutineScope()
    var setup by remember { mutableStateOf<SetupState?>(null) }
    var connStatus by remember { mutableStateOf<ConnectionStatus?>(null) }
    var testing by remember { mutableStateOf(false) }
    var watchedThreads by remember { mutableStateOf<Map<String, dev.pocketreviewer.util.PullRequest>>(emptyMap()) }

    LaunchedEffect(hasNotificationAccess) {
        setup = loadSetup()
        watchedThreads = PRNotificationListener.instance?.watchedThreads?.toMap() ?: emptyMap()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.status_title), style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary)

        // ── Setup checklist ──────────────────────────────────────────────────
        setup?.let { st ->
            Card(colors = CardDefaults.cardColors(
                containerColor = if (st.ready) MaterialTheme.colorScheme.surfaceVariant
                                 else MaterialTheme.colorScheme.errorContainer)) {
                Column(modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(if (st.ready) R.string.setup_ready else R.string.setup_incomplete),
                        style = MaterialTheme.typography.titleSmall)

                    StatusRow(stringResource(R.string.setup_notification_access),
                        if (st.notificationAccess) StatusLevel.OK else StatusLevel.ERROR,
                        stringResource(if (st.notificationAccess) R.string.status_granted else R.string.status_required))
                    if (!st.notificationAccess) {
                        Button(onClick = onRequestNotificationAccess,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(stringResource(R.string.setup_grant_access))
                        }
                    }
                    StatusRow(stringResource(R.string.setup_github_token),
                        if (st.githubToken) StatusLevel.OK else StatusLevel.ERROR,
                        stringResource(if (st.githubToken) R.string.status_set else R.string.status_required))
                    StatusRow(stringResource(R.string.setup_claude),
                        if (st.claude) StatusLevel.OK else StatusLevel.ERROR,
                        stringResource(if (st.claude) R.string.status_set else R.string.status_required))
                    if (st.googleChatEnabled) {
                        StatusRow(stringResource(R.string.setup_google_chat),
                            if (st.googleConnected) StatusLevel.OK else StatusLevel.WARN,
                            stringResource(if (st.googleConnected) R.string.status_connected else R.string.status_detection_only))
                    }
                    if (st.slackEnabled) {
                        StatusRow(stringResource(R.string.setup_slack),
                            if (st.slackConnected) StatusLevel.OK else StatusLevel.WARN,
                            stringResource(if (st.slackConnected) R.string.status_connected else R.string.status_detection_only))
                    }
                    if (!st.ready || (st.googleChatEnabled && !st.googleConnected) || (st.slackEnabled && !st.slackConnected)) {
                        OutlinedButton(onClick = onOpenSettings,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(stringResource(R.string.setup_open_settings))
                        }
                    }
                }
            }

            // ── Service ──────────────────────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.status_service), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    StatusRow(stringResource(R.string.status_listening),
                        if (st.listening) StatusLevel.OK else StatusLevel.WARN,
                        stringResource(if (st.listening) R.string.status_enabled else R.string.status_paused))
                    StatusRow(stringResource(R.string.status_mode), StatusLevel.INFO,
                        stringResource(if (st.autonomous) R.string.general_mode_autonomous else R.string.general_mode_manual))
                    StatusRow(stringResource(R.string.status_model), StatusLevel.INFO, st.model)
                    StatusRow(stringResource(R.string.status_battery),
                        if (batteryUnrestricted) StatusLevel.OK else StatusLevel.WARN,
                        stringResource(if (batteryUnrestricted) R.string.status_unrestricted else R.string.status_restricted))
                    if (!batteryUnrestricted) {
                        Text(stringResource(R.string.status_battery_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onRequestBatteryExemption,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) { Text(stringResource(R.string.status_battery_action)) }
                    }
                    StatusRow(stringResource(R.string.status_watched_threads),
                        if (watchedThreads.isEmpty()) StatusLevel.INFO else StatusLevel.OK,
                        watchedThreads.size.toString())
                }
            }
        }

        // ── Connection test ──────────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.status_connections), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = {
                            testing = true
                            connStatus = null
                            scope.launch {
                                connStatus = testConnections()
                                testing = false
                            }
                        },
                        enabled = !testing,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(stringResource(if (testing) R.string.status_testing else R.string.action_test))
                    }
                }

                connStatus?.let { s ->
                    ConnectionRow("GitHub", s.githubOk, StatusLevel.ERROR)
                    ConnectionRow("Claude", s.anthropicOk, StatusLevel.ERROR)
                    ConnectionRow("Google Chat", s.googleOk, StatusLevel.WARN)
                    ConnectionRow("Slack", s.slackOk, StatusLevel.WARN)
                    s.error?.let { err ->
                        Text(err, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }

                if (connStatus == null && !testing) {
                    Text(stringResource(R.string.status_test_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Watched threads
        if (watchedThreads.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.status_watched_threads), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.status_watched_threads_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    watchedThreads.forEach { (threadId, pr) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pr.key, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text(threadId.take(40) + if (threadId.length > 40) "…" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = {
                                PRNotificationListener.instance?.watchedThreads?.remove(threadId)
                                watchedThreads = watchedThreads - threadId
                            }) {
                                Text(stringResource(R.string.status_unwatch), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (watchedThreads.keys.last() != threadId) Divider()
                    }
                }
            }
        }

        // Latest log
        val lastLog = PRNotificationListener.lastLog
        if (lastLog.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))) {
                Column(modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.status_last_log), style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF94A3B8))
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(maxHeight = 200.dp)
                        .verticalScroll(rememberScrollState())) {
                        Text(lastLog,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp, lineHeight = 15.sp,
                            color = Color(0xFFE2E8F0))
                    }
                }
            }
        }
    }
}

enum class StatusLevel { OK, WARN, ERROR, INFO }

/** [ok] null = not configured. [failLevel] is how bad a failure is for this service. */
@Composable
private fun ConnectionRow(label: String, ok: Boolean?, failLevel: StatusLevel) {
    StatusRow(
        label,
        when (ok) { true -> StatusLevel.OK; false -> failLevel; null -> StatusLevel.INFO },
        stringResource(when (ok) {
            true -> R.string.status_ok
            false -> R.string.status_failed
            null -> R.string.status_not_configured
        })
    )
}

@Composable
fun StatusRow(label: String, level: StatusLevel, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(when (level) {
                StatusLevel.OK    -> "✅"
                StatusLevel.WARN  -> "⚠️"
                StatusLevel.ERROR -> "❌"
                StatusLevel.INFO  -> "ℹ️"
            }, fontSize = 14.sp)
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall,
            color = when (level) {
                StatusLevel.OK    -> Color(0xFF34D399)
                StatusLevel.WARN  -> Color(0xFFF59E0B)
                StatusLevel.ERROR -> MaterialTheme.colorScheme.error
                StatusLevel.INFO  -> MaterialTheme.colorScheme.onSurfaceVariant
            })
    }
}
