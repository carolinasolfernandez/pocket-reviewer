package dev.pocketreviewer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.pocketreviewer.R
import dev.pocketreviewer.api.ReviewManager
import dev.pocketreviewer.data.ReviewEntry
import dev.pocketreviewer.data.ReviewHistoryRepository
import dev.pocketreviewer.ui.CiStatusChip
import dev.pocketreviewer.util.PullRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(
    entries: List<ReviewEntry>,
    onClear: () -> Unit,
    onReReview: suspend (PullRequest) -> ReviewManager.ReviewResult,
    onPostToGitHub: suspend (PullRequest, String) -> Unit,
    onApprove: suspend (PullRequest) -> Unit,
    onApproveAndMerge: suspend (PullRequest) -> Unit,
    openPrKey: String? = null,
    onConsumedOpenPrKey: () -> Unit = {},
    onStartManualStep: (String, String, dev.pocketreviewer.service.ManualFlow.Stage, String) -> Unit,
) {
    var selectedEntry by remember { mutableStateOf<ReviewEntry?>(null) }

    // Deep link from the "PR detected" alert: open that PR's detail once it's loaded.
    // ONE-SHOT: consume (clear) openPrKey after the entry is found so background
    // refreshes of `entries` don't re-open the dialog after the user closes it.
    LaunchedEffect(openPrKey, entries) {
        val key = openPrKey ?: return@LaunchedEffect
        entries.firstOrNull { it.prKey == key }?.let { selectedEntry = it; onConsumedOpenPrKey() }
    }

    // Keep the open dialog in sync with history auto-refresh (stage/running changes).
    LaunchedEffect(entries) {
        selectedEntry?.let { sel -> selectedEntry = entries.firstOrNull { it.prKey == sel.prKey } ?: sel }
    }

    var showClearDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary)
            if (entries.isNotEmpty()) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.history_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.history_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { "${it.prKey}_${it.timestampMs}" }) { entry ->
                    HistoryCard(entry, onClick = { selectedEntry = entry })
                }
            }
        }
    }

    selectedEntry?.let { entry ->
        HistoryDetailDialog(
            entry = entry,
            onDismiss = { selectedEntry = null },
            onReReview = onReReview,
            onPostToGitHub = onPostToGitHub,
            onApprove = onApprove,
            onApproveAndMerge = onApproveAndMerge,
            onStartManualStep = onStartManualStep
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_message)) },
            confirmButton = {
                TextButton(onClick = { onClear(); showClearDialog = false }) {
                    Text(stringResource(R.string.history_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.history_cancel)) }
            }
        )
    }
}

@Composable
fun HistoryCard(entry: ReviewEntry, onClick: () -> Unit) {
    val verdictColor = when (entry.verdict) {
        "APPROVE"         -> Color(0xFF34D399)
        "REQUEST_CHANGES" -> Color(0xFFF59E0B)
        else              -> Color(0xFF94A3B8)
    }
    val verdictIcon = when (entry.verdict) {
        "APPROVE"         -> "✅"
        "REQUEST_CHANGES" -> "⚠️"
        else              -> "💬"
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(verdictIcon, fontSize = 20.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.prKey,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(formatTime(entry.timestampMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.status == "PENDING") StatusChip(stringResource(R.string.history_chip_pending), Color(0xFFF59E0B))
                    else if (entry.wasMerged)   StatusChip(stringResource(R.string.history_chip_merged), Color(0xFF7C3AED))
                    else if (entry.wasApproved) StatusChip(stringResource(R.string.history_chip_approved), Color(0xFF059669))
                    else if (entry.wasPosted)   StatusChip(stringResource(R.string.history_chip_commented), Color(0xFF6366F1))
                    if (entry.hasConflicts)     StatusChip(stringResource(R.string.history_chip_conflicts), Color(0xFFEF4444))
                }
            }
        }
    }
}

@Composable
fun StatusChip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.2f)) {
        Text(text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color)
    }
}

@Composable
fun HistoryDetailDialog(
    entry: ReviewEntry,
    onDismiss: () -> Unit,
    onReReview: suspend (PullRequest) -> ReviewManager.ReviewResult,
    onPostToGitHub: suspend (PullRequest, String) -> Unit,
    onApprove: suspend (PullRequest) -> Unit,
    onApproveAndMerge: suspend (PullRequest) -> Unit,
    onStartManualStep: (String, String, dev.pocketreviewer.service.ManualFlow.Stage, String) -> Unit,
) {
    val stage = dev.pocketreviewer.service.ManualFlow.parseStage(entry.manualStage)
    val inManualFlow = entry.manualStage != null &&
        stage != dev.pocketreviewer.service.ManualFlow.Stage.DONE
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var actionState by remember { mutableStateOf<ActionState>(ActionState.Idle) }
    val pr = remember { PullRequest.fromKey(entry.prKey, entry.prUrl) }

    val verdictLabel = when (entry.verdict) {
        "APPROVE"         -> stringResource(R.string.review_screen_verdict_approved)
        "REQUEST_CHANGES" -> stringResource(R.string.review_screen_verdict_issues)
        else              -> stringResource(R.string.review_screen_verdict_comments)
    }
    val verdictColor = when (entry.verdict) {
        "APPROVE"         -> Color(0xFF34D399)
        "REQUEST_CHANGES" -> Color(0xFFF59E0B)
        else              -> Color(0xFF94A3B8)
    }
    val cleanText = entry.reviewText
        .removePrefix("REQUEST_CHANGES").removePrefix("APPROVE").removePrefix("COMMENT")
        .trimStart('\n', ' ')

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // In the manual flow (gated in the app): show the current step + preview.
                if (inManualFlow) {
                    ManualStepPanel(entry, stage, onStartManualStep, onDismiss)
                } else {

                // Title + verdict chip + CI chip + GitHub link
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.review_screen_result_title), style = MaterialTheme.typography.titleMedium)
                        Text(entry.prKey,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(shape = RoundedCornerShape(6.dp), color = verdictColor.copy(alpha = 0.15f)) {
                            Text(verdictLabel,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall, color = verdictColor)
                        }
                        CiStatusChip(entry.ciFailingChecks)
                        if (entry.hasConflicts) ConflictStatusChip()
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(formatTime(entry.timestampMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.prUrl.isNotBlank()) {
                        TextButton(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.prUrl))) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("GitHub →", color = Color(0xFF6366F1), style = MaterialTheme.typography.bodySmall) }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(8.dp)) {
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 250.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)) {
                        Text(cleanText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp, lineHeight = 16.sp,
                            color = Color(0xFFE2E8F0))
                    }
                }

                // CI disclaimer (pending / no checks) before the actions
                CiDisclaimer(entry.ciState)
                if (entry.hasConflicts) ConflictDisclaimer()

                when (actionState) {
                    is ActionState.Idle -> {
                        // Notice about actions already taken — informational, does NOT block.
                        // The human decides whether to do it again.
                        if (entry.wasPosted || entry.wasApproved || entry.wasMerged) {
                            val done = buildList {
                                if (entry.wasPosted)   add(stringResource(R.string.history_done_commented))
                                if (entry.wasApproved) add(stringResource(R.string.history_done_approved))
                                if (entry.wasMerged)   add(stringResource(R.string.history_done_merged))
                            }.joinToString(" · ")
                            WarnBanner(stringResource(R.string.history_already_done, done))
                        }

                        Button(onClick = {
                            actionState = ActionState.Posting
                            coroutineScope.launch {
                                actionState = try {
                                    onPostToGitHub(pr, entry.reviewText)
                                    // Verdict reaction (COMMENT→telephone) on the Chat message.
                                    // For APPROVE we do NOT stamp anything here: :git-merge: only lands after a
                                    // real merge ("Approve and merge" button), not when posting.
                                    val newReaction = if (entry.verdict != "APPROVE")
                                        ReviewManager.reactOnEntry(context, entry, entry.verdict) else null
                                    val resolved = newReaction?.substringBefore("/reactions/")
                                    // Reply in the thread (same as autonomous/manual), not just react.
                                    if (entry.verdict == "COMMENT" || entry.verdict == "REQUEST_CHANGES") {
                                        ReviewManager.replyForEntry(context, entry, resolved)
                                    }
                                    ReviewHistoryRepository.updateEntry(context, entry.prKey, entry.timestampMs) {
                                        it.copy(
                                            wasPosted = true,
                                            activeReactionName = newReaction ?: it.activeReactionName,
                                            resolvedMessageName = resolved ?: it.resolvedMessageName
                                        )
                                    }
                                    ActionState.Posted
                                } catch (e: Exception) { ActionState.ActionError(e.message ?: context.getString(R.string.history_error_generic)) }
                            }
                        }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))) {
                            Text(stringResource(if (entry.wasPosted) R.string.history_post_again else R.string.history_post))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                actionState = ActionState.Approving
                                coroutineScope.launch {
                                    actionState = try {
                                        onApprove(pr)
                                        ReviewHistoryRepository.updateEntry(context, entry.prKey, entry.timestampMs) {
                                            it.copy(wasApproved = true)
                                        }
                                        ActionState.Approved
                                    } catch (e: Exception) { ActionState.ActionError(e.message ?: context.getString(R.string.history_error_generic)) }
                                }
                            }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(if (entry.wasApproved) R.string.history_approve_again else R.string.review_screen_approve))
                            }
                            Button(onClick = {
                                actionState = ActionState.Merging
                                coroutineScope.launch {
                                    actionState = try {
                                        onApproveAndMerge(pr)
                                        // :git-merge: reaction (emoji_approve) on the Chat message.
                                        // This used to be skipped: history merged on GitHub without touching Chat.
                                        val newReaction = ReviewManager.reactOnEntry(context, entry, "APPROVE")
                                        ReviewHistoryRepository.updateEntry(context, entry.prKey, entry.timestampMs) {
                                            it.copy(
                                                wasApproved = true, wasMerged = true,
                                                activeReactionName = newReaction ?: it.activeReactionName,
                                                resolvedMessageName = newReaction?.substringBefore("/reactions/")
                                                    ?: it.resolvedMessageName
                                            )
                                        }
                                        ActionState.Merged
                                    } catch (e: Exception) { ActionState.ActionError(e.message ?: context.getString(R.string.history_error_generic)) }
                                }
                            }, modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))) {
                                Text(stringResource(if (entry.wasMerged) R.string.history_approve_and_merge_again else R.string.review_screen_approve_and_merge))
                            }
                        }
                    }
                    is ActionState.Posting, is ActionState.Approving, is ActionState.Merging ->
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    is ActionState.Posted   -> SuccessBanner(stringResource(R.string.history_posted))
                    is ActionState.Approved -> SuccessBanner(stringResource(R.string.review_screen_approved))
                    is ActionState.Merged   -> SuccessBanner(stringResource(R.string.history_merged))
                    is ActionState.ActionError ->
                        Text((actionState as ActionState.ActionError).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    else -> {}
                }

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.action_close))
                }
                } // end else (entry already reviewed)
            }
        }
    }
}

@Composable
private fun ColumnScope.ManualStepPanel(
    entry: ReviewEntry,
    stage: dev.pocketreviewer.service.ManualFlow.Stage,
    onStartManualStep: (String, String, dev.pocketreviewer.service.ManualFlow.Stage, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Reply preview: exactly what will be posted (configurable message + URL if multi-PR).
    // null = message disabled in settings (blank): the step sends nothing.
    val threadReply by produceState<String?>(initialValue = null, entry.prKey, entry.prUrl, entry.isMultiPR) {
        value = ReviewManager.threadReplyText(context, entry.prUrl, entry.isMultiPR)
    }

    val (title, preview, confirmLabel) = when (stage) {
        dev.pocketreviewer.service.ManualFlow.Stage.REVIEW      -> Triple(stringResource(R.string.history_step_review_title, entry.prKey),
            stringResource(R.string.history_step_review_desc), stringResource(R.string.history_step_review_confirm))
        dev.pocketreviewer.service.ManualFlow.Stage.APPROVE     -> Triple(stringResource(R.string.history_step_approve_title, entry.prKey),
            stringResource(R.string.history_step_approve_desc), stringResource(R.string.history_step_approve_confirm))
        dev.pocketreviewer.service.ManualFlow.Stage.MERGE       -> Triple(stringResource(R.string.history_step_merge_title, entry.prKey),
            buildString {
                append(stringResource(R.string.history_step_merge_desc, entry.ciState))
                if (entry.ciFailingChecks.isNotEmpty()) {
                    append(" — ")
                    append(stringResource(R.string.history_step_merge_failing, entry.ciFailingChecks.take(3).joinToString(", ")))
                }
                if (entry.hasConflicts) {
                    append("  ")
                    append(stringResource(R.string.history_step_merge_conflicts))
                }
            }, stringResource(R.string.history_step_merge_confirm))
        dev.pocketreviewer.service.ManualFlow.Stage.POST_GITHUB -> Triple(stringResource(R.string.history_step_post_title, entry.prKey),
            stringResource(R.string.history_step_post_desc), stringResource(R.string.history_step_post_confirm))
        dev.pocketreviewer.service.ManualFlow.Stage.CHAT_REPLY  -> Triple(stringResource(R.string.history_step_reply_title, entry.prKey),
            threadReply?.let { "\"$it\"" } ?: stringResource(R.string.history_step_reply_empty), stringResource(R.string.history_step_reply_confirm))
        dev.pocketreviewer.service.ManualFlow.Stage.DONE        -> Triple(entry.prKey, "", "")
    }

    Text(title, style = MaterialTheme.typography.titleMedium)
    if (entry.prUrl.isNotBlank()) {
        TextButton(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.prUrl))) },
            contentPadding = PaddingValues(0.dp)
        ) { Text(stringResource(R.string.history_view_on_github), color = Color(0xFF6366F1), style = MaterialTheme.typography.bodySmall) }
    }
    Text(preview, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    // Show the review output once it exists (APPROVE / MERGE / POST_GITHUB stages).
    if (entry.reviewText.isNotBlank()) {
        val cleanText = entry.reviewText
            .removePrefix("REQUEST_CHANGES").removePrefix("APPROVE").removePrefix("COMMENT")
            .trimStart('\n', ' ')
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(8.dp)) {
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()).padding(10.dp)) {
                Text(cleanText, fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, lineHeight = 16.sp, color = Color(0xFFE2E8F0))
            }
        }
    }
    if (!entry.errorMessage.isNullOrBlank()) {
        Text("⚠️ ${entry.errorMessage}", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }

    if (entry.manualRunning) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        // At the APPROVE stage we offer "Approve and merge" (1 click) in addition to plain "Approve".
        if (stage == dev.pocketreviewer.service.ManualFlow.Stage.APPROVE) {
            Button(
                onClick = { onStartManualStep(entry.prKey, entry.prUrl, stage,
                    dev.pocketreviewer.service.ManualStepService.ACTION_APPROVE_MERGE) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
            ) { Text(stringResource(R.string.review_screen_approve_and_merge)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onStartManualStep(entry.prKey, entry.prUrl, stage, "confirm") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
            ) { Text(confirmLabel) }
            OutlinedButton(
                onClick = { onStartManualStep(entry.prKey, entry.prUrl, stage, "skip") },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.history_step_skip)) }
        }
    }
    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_close)) }
}

@Composable
fun WarnBanner(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF422006))) {
        Text(text, modifier = Modifier.padding(12.dp),
            color = Color(0xFFFBBF24),
            style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ms))
