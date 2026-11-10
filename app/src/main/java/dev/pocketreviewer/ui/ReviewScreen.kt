package dev.pocketreviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketreviewer.R
import dev.pocketreviewer.ui.theme.AppTheme
import dev.pocketreviewer.api.ReviewManager
import dev.pocketreviewer.config.ConfigStore
import dev.pocketreviewer.util.GitHubHost
import dev.pocketreviewer.util.PrLinks
import dev.pocketreviewer.util.PullRequest
import kotlinx.coroutines.launch

@Composable
fun ReviewScreen(
    runAnalysis: suspend (PullRequest) -> ReviewManager.ReviewResult,
    onPostToGitHub: suspend (PullRequest, String) -> Unit,
    onApprove: suspend (PullRequest) -> Unit,
    onApproveAndMerge: suspend (PullRequest) -> Unit,
) {
    val scope = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    // PR links are only recognized on the configured GitHub host.
    var githubHost by remember { mutableStateOf(GitHubHost.DEFAULT) }
    LaunchedEffect(Unit) { githubHost = ConfigStore.load(context).githubHost }

    var prUrl by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<ReviewUiState>(ReviewUiState.Idle) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scope)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.review_screen_title), style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary)

        // URL input
        OutlinedTextField(
            value = prUrl,
            onValueChange = {
                prUrl = it
                state = ReviewUiState.Idle
            },
            label = { Text(stringResource(R.string.review_screen_url_label)) },
            placeholder = { Text("https://$githubHost/owner/repo/pull/123") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            trailingIcon = {
                if (prUrl.isNotBlank()) {
                    TextButton(onClick = { prUrl = ""; state = ReviewUiState.Idle }) {
                        Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        )

        // Analyze button
        Button(
            onClick = {
                val pr = PrLinks.parse(prUrl, githubHost)
                if (pr == null) {
                    state = ReviewUiState.Error(context.getString(R.string.review_screen_invalid_url, githubHost))
                    return@Button
                }
                keyboard?.hide()
                state = ReviewUiState.Loading(pr)
                coroutineScope.launch {
                    state = try {
                        val result = runAnalysis(pr)
                        ReviewUiState.Done(pr, result)
                    } catch (e: Exception) {
                        val msg = buildString {
                            append(e.javaClass.simpleName)
                            append(": ")
                            append(e.message ?: context.getString(R.string.review_screen_no_message))
                            e.cause?.let { append(" | "); append(context.getString(R.string.review_screen_error_cause, it.message)) }
                        }
                        ReviewUiState.Error(msg)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = prUrl.isNotBlank() && state !is ReviewUiState.Loading
        ) {
            if (state is ReviewUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.review_screen_analyzing))
            } else {
                Text(stringResource(R.string.review_screen_analyze))
            }
        }

        // Loading state
        if (state is ReviewUiState.Loading) {
            val pr = (state as ReviewUiState.Loading).pr
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.review_screen_analyzing_pr, pr.key),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Error
        if (state is ReviewUiState.Error) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    (state as ReviewUiState.Error).message,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Result
        if (state is ReviewUiState.Done) {
            val done = state as ReviewUiState.Done
            var actionState by remember(state) { mutableStateOf<ActionState>(ActionState.Idle) }
            ReviewResultCard(
                pr            = done.pr,
                result        = done.result,
                actionState   = actionState,
                onPost        = {
                    actionState = ActionState.Posting
                    coroutineScope.launch {
                        actionState = try {
                            onPostToGitHub(done.pr, done.result.reviewText)
                            ActionState.Posted
                        } catch (e: Exception) {
                            ActionState.ActionError(context.getString(R.string.review_screen_post_error, e.message))
                        }
                    }
                },
                onApprove     = {
                    actionState = ActionState.Approving
                    coroutineScope.launch {
                        actionState = try {
                            onApprove(done.pr)
                            ActionState.Approved
                        } catch (e: Exception) {
                            ActionState.ActionError(context.getString(R.string.review_screen_approve_error, e.message))
                        }
                    }
                },
                onMerge       = {
                    actionState = ActionState.Merging
                    coroutineScope.launch {
                        actionState = try {
                            onApproveAndMerge(done.pr)
                            ActionState.Merged
                        } catch (e: Exception) {
                            ActionState.ActionError(e.message ?: context.getString(R.string.review_screen_merge_error))
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun ReviewResultCard(
    pr: PullRequest,
    result: ReviewManager.ReviewResult,
    actionState: ActionState,
    onPost: () -> Unit,
    onApprove: () -> Unit,
    onMerge: () -> Unit,
) {
    val verdictLabel = when {
        result.reviewText.startsWith("APPROVE")         -> stringResource(R.string.review_screen_verdict_approved)
        result.reviewText.startsWith("REQUEST_CHANGES") -> stringResource(R.string.review_screen_verdict_issues)
        else                                             -> stringResource(R.string.review_screen_verdict_comments)
    }
    val verdictColor = when {
        result.reviewText.startsWith("APPROVE")         -> AppTheme.colors.success
        result.reviewText.startsWith("REQUEST_CHANGES") -> AppTheme.colors.warning
        else                                             -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Strip the APPROVE/COMMENT/REQUEST_CHANGES prefix from the displayed text
    val cleanText = result.reviewText
        .removePrefix("REQUEST_CHANGES").removePrefix("APPROVE").removePrefix("COMMENT")
        .trimStart('\n', ' ')

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // Header: fixed title + PR key + verdict and CI chips
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.review_screen_result_title), style = MaterialTheme.typography.titleMedium)
                Text(pr.key, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(shape = RoundedCornerShape(6.dp), color = verdictColor.copy(alpha = 0.15f)) {
                    Text(verdictLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = verdictColor)
                }
                CiStatusChip(result.ciFailingChecks)
                if (result.hasConflicts) ConflictStatusChip()
            }
        }

        // Review text with copy button
        val clipboard = LocalClipboardManager.current
        var copied by remember { mutableStateOf(false) }

        Card(
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.codeBackground),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column {
                // Header with copy button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.review_screen_review_header), fontSize = 11.sp, color = AppTheme.colors.codeMuted,
                        fontFamily = FontFamily.Monospace)
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(result.reviewText))
                            copied = true
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.review_screen_copy),
                            modifier = Modifier.size(14.dp),
                            tint = if (copied) AppTheme.colors.success else AppTheme.colors.codeMuted)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (copied) stringResource(R.string.review_screen_copied) else stringResource(R.string.review_screen_copy),
                            fontSize = 11.sp,
                            color = if (copied) AppTheme.colors.success else AppTheme.colors.codeMuted
                        )
                    }
                }
                Divider(color = AppTheme.colors.codeDivider)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        cleanText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = AppTheme.colors.codeText
                    )
                }
            }
        }

        // CI disclaimer (pending / no checks) before the actions
        CiDisclaimer(result.ciState)
        // Merge-conflict warning (doesn't block the buttons in manual mode — the user decides)
        if (result.hasConflicts) ConflictDisclaimer()

        // Actions
        val isBusy = actionState is ActionState.Posting ||
            actionState is ActionState.Approving ||
            actionState is ActionState.Merging
        when (actionState) {
            is ActionState.Idle -> {
                Button(
                    onClick = onPost,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text(stringResource(R.string.review_screen_post_comments)) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(onClick = onApprove, modifier = Modifier.weight(1f),
                        enabled = !isBusy) {
                        Text(stringResource(R.string.review_screen_approve))
                    }
                    Button(
                        onClick = onMerge,
                        modifier = Modifier.weight(1f),
                        enabled = !isBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.merge)
                    ) { Text(stringResource(R.string.review_screen_merge)) }
                }
            }

            is ActionState.Posting, is ActionState.Approving, is ActionState.Merging -> {
                val label = when (actionState) {
                    is ActionState.Posting   -> stringResource(R.string.review_screen_posting)
                    is ActionState.Approving -> stringResource(R.string.review_screen_approving)
                    is ActionState.Merging   -> stringResource(R.string.review_screen_merging)
                    else -> ""
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(label, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            is ActionState.Posted -> {
                SuccessBanner(stringResource(R.string.review_screen_posted))
                ApproveActionsRow(onApprove, onMerge, enabled = !isBusy)
            }

            is ActionState.Approved -> SuccessBanner(stringResource(R.string.review_screen_approved))

            is ActionState.Merged   -> SuccessBanner(stringResource(R.string.review_screen_merged))

            is ActionState.ActionError -> {
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(actionState.message, modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall)
                }
                // Allow retrying
                if (!result.hasIssues) ApproveActionsRow(onApprove, onMerge, enabled = !isBusy)
            }
        }
    }
}

@Composable
fun SuccessBanner(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.successContainer)) {
        Text(text, modifier = Modifier.padding(12.dp),
            color = AppTheme.colors.onSuccessContainer,
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ApproveActionsRow(onApprove: () -> Unit, onMerge: () -> Unit, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onApprove, modifier = Modifier.weight(1f), enabled = enabled) {
            Text(stringResource(R.string.review_screen_approve))
        }
        Button(onClick = onMerge, modifier = Modifier.weight(1f),
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.merge)) {
            Text(stringResource(R.string.review_screen_approve_and_merge))
        }
    }
}

/**
 * CI disclaimer for manual mode: warns when the checks haven't finished yet
 * (PENDING) or when the PR has no checks (NONE), so the human doesn't
 * approve/merge blindly. FAILURE is already shown by the chip above.
 */
@Composable
fun CiDisclaimer(ciState: String) {
    val text = when (ciState) {
        "PENDING" -> stringResource(R.string.review_screen_ci_pending)
        "NONE"    -> stringResource(R.string.review_screen_ci_none)
        else      -> return
    }
    Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.warningContainer)) {
        Text(text, modifier = Modifier.padding(12.dp),
            color = AppTheme.colors.onWarningContainer,
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Red "merge conflicts" chip — the PR can't be merged until they are resolved. */
@Composable
fun ConflictStatusChip() {
    val color = MaterialTheme.colorScheme.error
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.15f)) {
        Text(stringResource(R.string.review_screen_conflicts_chip),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color)
    }
}

/** Prominent merge-conflict warning, shown before the action buttons. */
@Composable
fun ConflictDisclaimer() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(stringResource(R.string.review_screen_conflicts_warning),
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun CiStatusChip(ciFailingChecks: List<String>) {
    if (ciFailingChecks.isEmpty()) return
    val ciColor = AppTheme.colors.warning
    Surface(shape = RoundedCornerShape(6.dp), color = ciColor.copy(alpha = 0.15f)) {
        val label = ciFailingChecks.take(2).joinToString(", ") +
            if (ciFailingChecks.size > 2) " +${ciFailingChecks.size - 2}" else ""
        Text("⚠️ CI: $label",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = ciColor)
    }
}

// ── UI states ───────────────────────────────────────────────────────────────

sealed class ReviewUiState {
    object Idle : ReviewUiState()
    data class Loading(val pr: PullRequest) : ReviewUiState()
    data class Error(val message: String) : ReviewUiState()
    data class Done(
        val pr: PullRequest,
        val result: ReviewManager.ReviewResult,
    ) : ReviewUiState()
}

sealed class ActionState {
    object Idle      : ActionState()
    object Posting   : ActionState()
    object Posted    : ActionState()
    object Approving : ActionState()
    object Approved  : ActionState()
    object Merging   : ActionState()
    object Merged    : ActionState()
    data class ActionError(val message: String) : ActionState()
}
