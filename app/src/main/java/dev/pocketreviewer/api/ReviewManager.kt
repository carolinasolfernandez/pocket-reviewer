package dev.pocketreviewer.api

import android.content.Context
import android.util.Log
import dev.pocketreviewer.config.AppConfig
import dev.pocketreviewer.config.ConfigStore
import dev.pocketreviewer.config.Keys
import dev.pocketreviewer.config.SecretStore
import dev.pocketreviewer.data.KnowledgeBaseRepository
import dev.pocketreviewer.messaging.Messaging
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pocketreviewer.data.ReviewEntry
import dev.pocketreviewer.data.ReviewHistoryRepository
import dev.pocketreviewer.util.PullRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

val Context.dataStore by preferencesDataStore(name = "settings")

object ReviewManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // Verdict markers Claude emits. Order doesn't matter (none is a prefix of another).
    private val VERDICT_MARKERS = listOf("REQUEST_CHANGES", "APPROVE", "COMMENT")

    /**
     * Trims the reasoning the model sometimes writes BEFORE the verdict marker. Leaves
     * the text starting at the marker (marker included), so verdict detection
     * (startsWith) and posting work. If it already starts with a marker, or there is no
     * marker line at all, the text is returned as is.
     */
    internal fun normalizeReviewText(raw: String): String {
        val trimmed = raw.trim()
        if (VERDICT_MARKERS.any { trimmed.startsWith(it) }) return trimmed
        val lines = trimmed.lines()
        fun bareToken(line: String) = line.trim().trim('*', '#', '`', ':', ' ', '-', '>').uppercase()
        for (i in lines.indices) {
            // (a) A line that IS just the marker (with decoration: **COMMENT**, ## COMMENT, "COMMENT:").
            //     It is normalized to the bare token.
            val bare = bareToken(lines[i])
            if (VERDICT_MARKERS.contains(bare)) {
                val rest = lines.drop(i + 1).joinToString("\n").trimStart('\n')
                return if (rest.isBlank()) bare else "$bare\n\n$rest"
            }
            // (b) Marker AT THE START of the line followed by inline content ("COMMENT: summary",
            //     "REQUEST_CHANGES - foo"). This used to slip through and the preceding reasoning
            //     leaked into the GitHub comment. We keep the marker + the inline summary.
            val lead = lines[i].trimStart('*', '#', '`', '>', ' ', '\t')
            val inlineMarker = VERDICT_MARKERS.firstOrNull { m ->
                lead.startsWith(m) && lead.length > m.length &&
                    !lead[m.length].isLetterOrDigit() && lead[m.length] != '_'
            }
            if (inlineMarker != null) {
                return (listOf(lead) + lines.drop(i + 1)).joinToString("\n").trim()
            }
        }
        return trimmed
    }

    /** Text of the "I left comments" reply for the thread. Multi-PR appends the URL. Pure → testable. */
    internal fun buildThreadReply(baseMessage: String, prUrl: String, isMultiPR: Boolean): String =
        if (isMultiPR) "$baseMessage — $prUrl" else baseMessage

    /**
     * Reads the configurable message (thread_msg) and builds the reply for [prUrl].
     * null = message disabled (saved blank): nothing is sent to the chat.
     */
    suspend fun threadReplyText(context: Context, prUrl: String, isMultiPR: Boolean): String? {
        val prefs = context.dataStore.data.first()
        val base = ChatMessages.resolve(
            prefs[stringPreferencesKey(ChatMessages.KEY_THREAD_MSG)], ChatMessages.DEFAULT_THREAD_MSG
        ) ?: return null
        return buildThreadReply(base, prUrl, isMultiPR)
    }

    /** true if the thread reply is enabled (the setting was not saved blank). */
    suspend fun isThreadReplyConfigured(context: Context): Boolean {
        val prefs = context.dataStore.data.first()
        return ChatMessages.resolve(
            prefs[stringPreferencesKey(ChatMessages.KEY_THREAD_MSG)], ChatMessages.DEFAULT_THREAD_MSG
        ) != null
    }

    data class ReviewResult(
        val hasIssues: Boolean,
        val reviewText: String = "",
        val verdict: String = "APPROVE",
        val ciFailingChecks: List<String> = emptyList(),
        // Overall CI state: "NONE" (no checks) | "PENDING" (running) |
        // "SUCCESS" | "FAILURE" | "UNKNOWN" (could not be determined)
        val ciState: String = "UNKNOWN",
        // true if the verdict left comments on the PR (COMMENT or REQUEST_CHANGES)
        val leftComments: Boolean = false,
        // true if the PR has real merge conflicts (mergeable=false / "dirty")
        val hasConflicts: Boolean = false,
        // raw GitHub mergeable_state: clean|dirty|behind|blocked|unstable|unknown
        val mergeableState: String = "unknown",
        // Chat message name resolved through the working reaction (…/messages/X, without
        // /reactions/…). The unified Gmail/Chat notification format carries
        // messageName=null, so the autonomous flow uses this to put the approve emoji
        // (AppConfig.emojiApprove) on the real message.
        val resolvedMessageName: String? = null
    )

    // CI status of a PR. [state] sums up the situation; the lists give the detail.
    data class CiStatus(
        val state: String,                       // NONE | PENDING | SUCCESS | FAILURE
        val failingChecks: List<String> = emptyList(),
        val pendingChecks: List<String> = emptyList()
    )

    // Analyzes the PR and returns the review WITHOUT posting anything (for manual mode)
    suspend fun analyzeOnly(context: Context, pr: PullRequest): ReviewResult = withContext(Dispatchers.IO) {
        val githubToken = getGithubToken(context)
        val config = ConfigStore.load(context)
        val prData = fetchPRData(pr, githubToken, config, context)
        val kb = KnowledgeBaseRepository.effective(context)
        val review = callClaude(context, prData, kb, config)
        val verdict = when {
            review.startsWith("APPROVE")         -> "APPROVE"
            review.startsWith("REQUEST_CHANGES") -> "REQUEST_CHANGES"
            else -> "COMMENT"
        }
        val ci = runCatching { getCiStatus(pr, githubToken, config.ignoredChecks) }.getOrDefault(CiStatus("UNKNOWN"))
        val hasConflicts = MergePolicy.hasConflict(prData.mergeable, prData.mergeableState)
        ReviewHistoryRepository.upsertReviewed(context, ReviewEntry(
            prKey = pr.key, prUrl = pr.url,
            verdict = verdict, reviewText = review,
            timestampMs = System.currentTimeMillis(),
            ciFailingChecks = ci.failingChecks,
            ciState = ci.state,
            hasConflicts = hasConflicts,
            mergeableState = prData.mergeableState ?: "unknown"
        ))
        ReviewResult(
            hasIssues = review.startsWith("REQUEST_CHANGES"),
            reviewText = review,
            verdict = verdict,
            ciFailingChecks = ci.failingChecks,
            ciState = ci.state,
            leftComments = verdict in listOf("COMMENT", "REQUEST_CHANGES"),
            hasConflicts = hasConflicts,
            mergeableState = prData.mergeableState ?: "unknown"
        )
    }

    // Posts the already generated review to GitHub
    suspend fun postAnalysis(context: Context, pr: PullRequest, reviewText: String) = withContext(Dispatchers.IO) {
        val githubToken = getGithubToken(context)
        postReview(pr, reviewText, githubToken)
    }

    suspend fun runReview(context: Context, pr: PullRequest): ReviewResult {
        val githubToken = getGithubToken(context)
        val config = ConfigStore.load(context)

        // 1. Fetch the PR info and the diff
        val prData = fetchPRData(pr, githubToken, config, context)

        // 2. Call Claude with the configured knowledge base
        val kb = KnowledgeBaseRepository.effective(context)
        val review = callClaude(context, prData, kb, config)

        val hasIssues = review.startsWith("REQUEST_CHANGES")

        val verdict = when {
            review.startsWith("APPROVE")         -> "APPROVE"
            review.startsWith("REQUEST_CHANGES") -> "REQUEST_CHANGES"
            else -> "COMMENT"
        }

        val leftComments = verdict in listOf("COMMENT", "REQUEST_CHANGES")

        // 3. Post comments if the verdict left any (COMMENT or REQUEST_CHANGES)
        if (leftComments) {
            postReview(pr, review, githubToken)
        }

        // 4. CI status — warning only, does not block
        val ci = runCatching { getCiStatus(pr, githubToken, config.ignoredChecks) }.getOrDefault(CiStatus("UNKNOWN"))
        val hasConflicts = MergePolicy.hasConflict(prData.mergeable, prData.mergeableState)

        ReviewHistoryRepository.upsertReviewed(context, ReviewEntry(
            prKey = pr.key, prUrl = pr.url,
            verdict = verdict, reviewText = review,
            timestampMs = System.currentTimeMillis(),
            wasPosted = leftComments,
            ciFailingChecks = ci.failingChecks,
            ciState = ci.state,
            hasConflicts = hasConflicts,
            mergeableState = prData.mergeableState ?: "unknown"
        ))
        return ReviewResult(hasIssues = hasIssues, reviewText = review, verdict = verdict,
            ciFailingChecks = ci.failingChecks, ciState = ci.state, leftComments = leftComments,
            hasConflicts = hasConflicts, mergeableState = prData.mergeableState ?: "unknown")
    }

    // Configurable emojis (loaded from settings). A spec can be a literal unicode
    // emoji ("👀", "💬", "✅") or the shortcode of a custom emoji ("ship-it").
    data class EmojiSpecs(
        val working: String,
        val approve: String,
        val comment: String
    )

    private suspend fun loadEmojiSpecs(context: Context): EmojiSpecs {
        val c = ConfigStore.load(context)
        return EmojiSpecs(working = c.emojiWorking, approve = c.emojiApprove, comment = c.emojiComment)
    }

    /** Variant of runReview that also handles chat reactions when there is a chat context */
    suspend fun runReviewFull(context: Context, pr: PullRequest, chatContext: ChatContext? = null, isMultiPR: Boolean = false): ReviewResult {
        val specs = loadEmojiSpecs(context)

        // 1. Put the "working" emoji — keeps the reaction name so it can be removed later
        android.util.Log.d("ReviewManager", "runReviewFull: pr=${pr.key} hintSpace=${chatContext?.spaceName ?: "NULL"}")
        var eyesReactionName: String? = null
        runCatching {
            eyesReactionName = Messaging.reactToPrMessage(
                context, pr.url, chatContext?.spaceName, specs.working
            )
        }.onFailure { android.util.Log.w("ReviewManager", "addEyesReaction failed: ${it.message}") }

        // 2. Run the review. If it fails, the working emoji is not swapped for any other
        //    one — the error is internal and is only shown in the app's notification.
        val result = try {
            runReview(context, pr)
        } catch (e: Exception) {
            throw e
        }
        // Real message resolved by the working reaction ("spaces/S/messages/M"). The
        // unified Gmail/Chat notification format carries the space as an id that is NOT a
        // valid REST API resource name; so to reply we ALWAYS use the resolved name (same
        // as for reactions), not the notification's chatContext.
        val resolvedMessageName = eyesReactionName?.let { Messaging.messageOf(it) }

        // The approve emoji (AppConfig.emojiApprove, ✅ by default) signals that the PR was
        // actually approved/merged, not that the review verdict was APPROVE. So for APPROVE
        // we do NOT stamp the verdict emoji here: we leave the working emoji (👀) and
        // register it as the active reaction so the aggregator replaces it with the approve
        // emoji only once the PR (or every PR in the message) is merged.
        // COMMENT/REQUEST_CHANGES do switch to their emoji (AppConfig.emojiComment, 💬).
        val eyesName = eyesReactionName
        if (result.verdict == "APPROVE") {
            if (eyesName != null && resolvedMessageName != null) {
                Messaging.setActiveReaction(resolvedMessageName, eyesName)
            }
        } else {
            swapEyesReaction(context, eyesName, result.verdict)
        }

        // 3. Reply in the thread if comments were left on the PR (COMMENT and REQUEST_CHANGES are equivalent)
        if (chatContext != null && result.verdict in listOf("REQUEST_CHANGES", "COMMENT")) {
            // null = message disabled in settings: nothing is written to the chat.
            val replyText = threadReplyText(context, pr.url, isMultiPR)
            if (replyText != null) {
                val ok = runCatching {
                    Messaging.reply(context, resolvedMessageName, chatContext, replyText)
                }.onFailure { android.util.Log.w("ReviewManager", "reply threw: ${it.message}") }
                    .getOrDefault(false)
                if (!ok) android.util.Log.w("ReviewManager", "reply was not posted for ${pr.key}")
            }
        }
        // Expose the real message so the autonomous flow can put the approve emoji
        // even if the notification came with messageName=null.
        return result.copy(resolvedMessageName = resolvedMessageName)
    }

    /**
     * Removes [removeReactionName] (and any other tracked active reaction on the
     * message), then adds the configured emoji for [verdict]. Returns the new
     * reaction's resource name (or null). Public so the manual flow can persist it.
     */
    suspend fun swapReaction(
        context: Context,
        messageName: String,
        removeReactionName: String?,
        verdict: String
    ): String? {
        removeReactionName?.let {
            runCatching { Messaging.removeReaction(context, it) }
                .onFailure { e -> android.util.Log.w("ReviewManager", "remove reaction failed: ${e.message}") }
        }
        Messaging.getAndClearActiveReaction(messageName)?.let { prev ->
            if (prev != removeReactionName) {
                runCatching { Messaging.removeReaction(context, prev) }
                    .onFailure { e -> android.util.Log.w("ReviewManager", "remove prev reaction failed: ${e.message}") }
            }
        }

        val specs = loadEmojiSpecs(context)
        val spec = when (verdict) {
            "APPROVE"                    -> specs.approve
            "COMMENT", "REQUEST_CHANGES" -> specs.comment
            else                         -> return null
        }

        // We only react with the configured emoji (or whatever Messaging.react resolves it
        // to). We never force an emoji the user did not choose.
        val newReactionName = Messaging.react(context, messageName, spec)
        newReactionName?.let { Messaging.setActiveReaction(messageName, it) }
        return newReactionName
    }

    private suspend fun swapEyesReaction(context: Context, eyesReactionName: String?, kind: String) {
        val messageName = eyesReactionName?.substringBefore("/reactions/") ?: return
        swapReaction(context, messageName, eyesReactionName, kind)
    }

    /**
     * Initial "working" reaction (👀 by default) on the PR's message (used by the gated
     * manual flow, to match what runReviewFull does). Returns the reaction name or null.
     */
    suspend fun addWorkingReaction(context: Context, pr: PullRequest, chatContext: ChatContext?): String? {
        if (chatContext == null) return null
        val specs = loadEmojiSpecs(context)
        return runCatching {
            Messaging.reactToPrMessage(context, pr.url, chatContext.spaceName, specs.working)
        }.onFailure { android.util.Log.w("ReviewManager", "addWorkingReaction failed: ${it.message}") }.getOrNull()
    }


    /**
     * Reacts on the chat message of a history entry with the emoji configured for
     * [verdict] (APPROVE→AppConfig.emojiApprove, COMMENT→AppConfig.emojiComment). Used by
     * the actions triggered from the history UI (Post / Approve and merge), which used to
     * touch GitHub only — so the approve emoji never landed when merging from the history.
     *
     * Uses the already resolved message ([ReviewEntry.resolvedMessageName], fast path). If
     * there is none (old entry, or one reviewed in autonomous mode, which does not persist
     * it), it RE-RESOLVES by locating the PR's message — leaving a transient working emoji
     * that the swap replaces right away. No-op if the entry has no chat context. Returns the
     * resource name of the new reaction.
     */
    suspend fun reactOnEntry(context: Context, entry: ReviewEntry, verdict: String): String? {
        var target = entry.resolvedMessageName ?: entry.chatMessageName
        var removeName = entry.activeReactionName
        if (target == null) {
            val chat = entry.chatSpaceName?.let { ChatContext(it, entry.chatMessageName, entry.chatThreadName) }
                ?: return null
            val eyes = addWorkingReaction(context, PullRequest.fromKey(entry.prKey, entry.prUrl), chat)
            target = eyes?.substringBefore("/reactions/") ?: return null
            removeName = eyes
        }
        return swapReaction(context, target, removeName, verdict)
    }

    /**
     * Replies in the chat thread of [entry] with the configured message (same as the
     * autonomous/manual flow). Prefers the resolved message [resolvedMessageName]
     * (chime-safe); otherwise falls back to the entry's one / the notification context.
     * No-op (false) if the entry has no chat context. Used by the history's "Post" button,
     * which used to only post to GitHub + react, without replying in the thread like the
     * other flows.
     */
    suspend fun replyForEntry(context: Context, entry: ReviewEntry, resolvedMessageName: String?): Boolean {
        val chat = entry.chatSpaceName?.let { ChatContext(it, entry.chatMessageName, entry.chatThreadName) }
            ?: return false
        // null = message disabled in settings: nothing is sent and it is not an error.
        val text = threadReplyText(context, entry.prUrl, entry.isMultiPR) ?: return false
        return Messaging.reply(context, resolvedMessageName ?: entry.resolvedMessageName, chat, text)
    }

    suspend fun isAutonomousMode(context: Context): Boolean = ConfigStore.load(context).autonomous

    suspend fun isNotificationAnalysisEnabled(context: Context): Boolean =
        ConfigStore.load(context).notificationAnalysis

    private suspend fun getGithubToken(context: Context): String {
        return SecretStore.get(context, Keys.GITHUB_TOKEN)
            ?: throw Exception("GitHub token is not configured (Settings → Accounts)")
    }

    /**
     * Executes [req] with exponential backoff + jitter on HTTP 429 or >=500 (incl. 529).
     * Honors a `Retry-After` header if present. Returns the last Response on success or
     * after exhausting [maxAttempts]; the caller is responsible for checking isSuccessful.
     */
    private fun executeWithRetry(req: Request, maxAttempts: Int = 3): Response {
        val baseSleepMs = 1000L
        val capMs = 8000L
        var lastResponse: Response? = null
        for (attempt in 0 until maxAttempts) {
            val response = client.newCall(req).execute()
            val code = response.code
            val shouldRetry = code == 429 || code == 529 || code >= 500
            if (!shouldRetry || attempt == maxAttempts - 1) {
                // Return this response (caller checks isSuccessful)
                lastResponse = response
                break
            }
            // Retry — consume body and close before sleeping
            val retryAfterSec = response.header("Retry-After")?.toLongOrNull()
            response.body?.close()
            val sleepMs = if (retryAfterSec != null) {
                retryAfterSec * 1000L
            } else {
                val exponential = baseSleepMs * (1L shl attempt) // base * 2^attempt
                minOf(exponential, capMs) + (Math.random() * 200).toLong() // +jitter up to 200ms
            }
            Log.w("ReviewManager", "HTTP $code — retry ${attempt + 1}/$maxAttempts after ${sleepMs}ms")
            Thread.sleep(sleepMs)
        }
        return lastResponse ?: client.newCall(req).execute()
    }

    private fun ghGet(url: String, token: String, accept: String = "application/vnd.github.v3+json"): String {
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", accept)
            .build()
        val resp = executeWithRetry(req)
        val body = resp.body?.string() ?: ""
        if (!resp.isSuccessful) throw Exception("GitHub API ${resp.code} for $url: $body")
        return body
    }

    /**
     * Fetches all pages of a GitHub list endpoint (JSON array response) by following
     * the `Link: <url>; rel="next"` header. Returns the concatenated JsonArray.
     * Falls back gracefully: if a page has exactly 100 items but no Link header,
     * appends a sentinel object `{"_truncated": true}` so callers can detect it.
     */
    private fun ghGetAllPages(firstUrl: String, token: String): JsonArray {
        val combined = mutableListOf<JsonElement>()
        var nextUrl: String? = firstUrl
        while (nextUrl != null) {
            val req = Request.Builder()
                .url(nextUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()
            val resp = executeWithRetry(req)
            val bodyStr = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw Exception("GitHub API ${resp.code} for $nextUrl: $bodyStr")

            val page = json.parseToJsonElement(bodyStr)
                .let { if (it is JsonArray) it else JsonArray(emptyList()) }
            combined.addAll(page)

            // Parse Link header for rel="next"
            val linkHeader = resp.header("Link") ?: ""
            nextUrl = parseLinkNext(linkHeader)

            // No Link header and exactly 100 items → likely truncated
            if (nextUrl == null && page.size == 100 && linkHeader.isBlank()) {
                Log.w("ReviewManager", "ghGetAllPages: got 100 items with no Link header from $firstUrl — may be truncated")
                combined.add(buildJsonObject { put("_truncated", true) })
            }
        }
        return JsonArray(combined)
    }

    /** Extracts the URL for rel="next" from a GitHub Link header, or null if absent. */
    private fun parseLinkNext(linkHeader: String): String? {
        if (linkHeader.isBlank()) return null
        // Example: <https://api.github.com/...?page=2>; rel="next", <...>; rel="last"
        return linkHeader.split(",").firstOrNull { it.contains("rel=\"next\"") }
            ?.let { segment ->
                val start = segment.indexOf('<')
                val end = segment.indexOf('>')
                if (start >= 0 && end > start) segment.substring(start + 1, end) else null
            }
    }

    /**
     * GitHub computes `mergeable` asynchronously: the first GET on a PR right after a
     * push usually returns `mergeable: null` / `mergeable_state: "unknown"`. Re-GET a
     * few times until it resolves, so we don't read "still computing" as "no conflict".
     * Returns (mergeable, mergeable_state); falls back to (null, last-known-state).
     */
    private fun pollMergeable(
        base: String, number: Int, token: String,
        initial: JsonObject?, maxAttempts: Int = 4, delayMs: Long = 1500L
    ): Pair<Boolean?, String?> {
        var obj = initial
        var lastState: String? = initial?.get("mergeable_state")?.jsonPrimitive?.contentOrNull
        repeat(maxAttempts) { attempt ->
            if (obj == null) {
                obj = runCatching {
                    json.parseToJsonElement(ghGet("$base/pulls/$number", token)).jsonObject
                }.getOrNull()
            }
            val m = obj?.get("mergeable")?.jsonPrimitive?.booleanOrNull
            val s = obj?.get("mergeable_state")?.jsonPrimitive?.contentOrNull
            if (s != null) lastState = s
            if (m != null) return m to s
            if (attempt < maxAttempts - 1) Thread.sleep(delayMs)
            obj = null  // force a fresh GET next iteration
        }
        return null to lastState
    }

    private fun fetchPRData(pr: PullRequest, token: String, config: AppConfig, context: Context? = null): PRData {
        val base = "https://api.github.com/repos/${pr.owner}/${pr.repo}"

        // ── Core PR data ──────────────────────────────────────────────────────
        val prInfo = json.parseToJsonElement(
            ghGet("$base/pulls/${pr.number}", token)
        ).jsonObject

        // ── Mergeability (conflicts) ──────────────────────────────────────────
        val (mergeable, mergeableState) = pollMergeable(base, pr.number, token, initial = prInfo)
        val isDraft = prInfo["draft"]?.jsonPrimitive?.booleanOrNull ?: false

        val diffText = ghGet(
            "$base/pulls/${pr.number}", token,
            accept = "application/vnd.github.v3.diff"
        )

        val files = ghGetAllPages("$base/pulls/${pr.number}/files?per_page=100", token)

        val headRef = prInfo["head"]?.jsonObject?.get("ref")?.jsonPrimitive?.content ?: ""

        // ── 1. Existing comments & reviews ───────────────────────────────────
        val inlineComments = ghGetAllPages("$base/pulls/${pr.number}/comments?per_page=100", token)

        val issueComments = ghGetAllPages("$base/issues/${pr.number}/comments?per_page=100", token)

        val existingReviews = ghGetAllPages("$base/pulls/${pr.number}/reviews?per_page=100", token)

        // ── 2. The repo's CLAUDE.md / AGENTS.md ──────────────────────────────
        val repoRules = listOf("CLAUDE.md", "AGENTS.md").firstNotNullOfOrNull { filename ->
            try {
                val resp = ghGet("$base/contents/$filename", token)
                val obj = json.parseToJsonElement(resp).jsonObject
                val encoded = obj["content"]?.jsonPrimitive?.content ?: return@firstNotNullOfOrNull null
                val decoded = android.util.Base64.decode(encoded.replace("\n", ""), android.util.Base64.DEFAULT)
                String(decoded).takeIf { it.isNotBlank() }?.let { "$filename:\n$it" }
            } catch (e: Exception) { null }
        }

        // ── 3. Related PRs: same branch in the sibling repos the user configured ──
        //    (same owner). Empty list = feature off.
        val siblingRepos = config.relatedRepos
            .map { it.substringAfterLast('/') }
            .filter { it.isNotBlank() && !it.equals(pr.repo, ignoreCase = true) }
            .distinct()

        val relatedPRs = if (headRef.isNotBlank()) {
            siblingRepos.mapNotNull { repo ->
                try {
                    val branchCheck = ghGet(
                        "https://api.github.com/repos/${pr.owner}/$repo/branches/$headRef", token
                    )
                    if (branchCheck.isBlank() || branchCheck.contains("\"message\":\"Branch not found\"")) return@mapNotNull null

                    val prs = json.parseToJsonElement(
                        ghGet("https://api.github.com/repos/${pr.owner}/$repo/pulls?head=${pr.owner}:$headRef&state=open", token)
                    ).let { if (it is JsonArray) it else JsonArray(emptyList()) }

                    val relatedPR = prs.firstOrNull()?.jsonObject ?: return@mapNotNull null
                    val relatedNumber = relatedPR["number"]?.jsonPrimitive?.int ?: return@mapNotNull null
                    val relatedTitle = relatedPR["title"]?.jsonPrimitive?.content ?: ""
                    val relatedUrl = relatedPR["html_url"]?.jsonPrimitive?.content ?: ""

                    val relatedDiff = ghGet(
                        "https://api.github.com/repos/${pr.owner}/$repo/pulls/$relatedNumber",
                        token, accept = "application/vnd.github.v3.diff"
                    ).take(4000)

                    RelatedPR(repo, relatedNumber, relatedTitle, relatedUrl, relatedDiff)
                } catch (e: Exception) { null }
            }
        } else emptyList()

        // ── 4. The PR's commits, for incremental re-review ────────────────────
        val commits = ghGetAllPages("$base/pulls/${pr.number}/commits?per_page=100", token)

        val commitShas = commits.mapNotNull { it.jsonObject["sha"]?.jsonPrimitive?.content }

        // Check the history for a previous review of this PR
        val previousReview = context?.let {
            ReviewHistoryRepository.load(it).find { entry -> entry.prKey == pr.key }
        }

        return PRData(
            prInfo = prInfo,
            diffText = diffText,
            files = files,
            inlineComments = inlineComments,
            issueComments = issueComments,
            existingReviews = existingReviews,
            repoRules = repoRules,
            relatedPRs = relatedPRs,
            commitShas = commitShas,
            previousReview = previousReview,
            mergeable = mergeable,
            mergeableState = mergeableState,
            isDraft = isDraft
        )
    }

    private suspend fun callClaude(context: Context, data: PRData, knowledgeBase: String, config: AppConfig): String {
        val title   = data.prInfo["title"]?.jsonPrimitive?.content ?: "No title"
        val author  = data.prInfo["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content ?: "?"
        val body    = data.prInfo["body"]?.jsonPrimitive?.contentOrNull ?: "No description"
        val headRef = data.prInfo["head"]?.jsonObject?.get("ref")?.jsonPrimitive?.content ?: "?"
        val baseRef = data.prInfo["base"]?.jsonObject?.get("ref")?.jsonPrimitive?.content ?: "?"

        // Filter out the sentinel object injected by ghGetAllPages when pagination may be incomplete
        val realFiles = data.files.filter { it.jsonObject["_truncated"] == null }
        val filesTruncated = data.files.any { it.jsonObject["_truncated"]?.jsonPrimitive?.booleanOrNull == true }

        val fileList = buildString {
            realFiles.joinTo(this, "\n") { f ->
                val obj = f.jsonObject
                "- ${obj["filename"]?.jsonPrimitive?.content} " +
                "(+${obj["additions"]?.jsonPrimitive?.int}/-${obj["deletions"]?.jsonPrimitive?.int})"
            }
            if (filesTruncated) append("\n(truncated: showing first 100 files)")
        }

        val stack = ReviewPrompt.detectStack(
            realFiles.mapNotNull { it.jsonObject["filename"]?.jsonPrimitive?.contentOrNull }
        )

        // ── Format the existing comments ──────────────────────────────────────
        val existingCommentsSection = buildString {
            // Previous reviews
            val meaningfulReviews = data.existingReviews.filter { r ->
                val state = r.jsonObject["state"]?.jsonPrimitive?.content
                val reviewBody = r.jsonObject["body"]?.jsonPrimitive?.contentOrNull
                state != "PENDING" && !reviewBody.isNullOrBlank()
            }
            if (meaningfulReviews.isNotEmpty()) {
                append("\n## EXISTING REVIEWS\n")
                meaningfulReviews.forEach { r ->
                    val reviewer = r.jsonObject["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content
                    val state = r.jsonObject["state"]?.jsonPrimitive?.content
                    val reviewBody = r.jsonObject["body"]?.jsonPrimitive?.contentOrNull ?: ""
                    append("**[$state] @$reviewer:** ${reviewBody.take(500)}\n")
                }
            }

            // Inline comments on the diff (drop the pagination `_truncated` sentinel)
            val realInlineComments = data.inlineComments.filter { it.jsonObject["_truncated"] == null }
            if (realInlineComments.isNotEmpty()) {
                append("\n## INLINE REVIEW COMMENTS (on diff)\n")
                realInlineComments.take(20).forEach { c ->
                    val commenter = c.jsonObject["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content
                    val path = c.jsonObject["path"]?.jsonPrimitive?.content
                    val line = c.jsonObject["line"]?.jsonPrimitive?.intOrNull ?: c.jsonObject["original_line"]?.jsonPrimitive?.intOrNull
                    val commentBody = c.jsonObject["body"]?.jsonPrimitive?.contentOrNull ?: ""
                    append("**@$commenter** on `$path:$line`: ${commentBody.take(300)}\n")
                }
            }

            // General PR comments (drop the pagination `_truncated` sentinel)
            val realIssueComments = data.issueComments.filter { it.jsonObject["_truncated"] == null }
            if (realIssueComments.isNotEmpty()) {
                append("\n## PR DISCUSSION COMMENTS\n")
                realIssueComments.take(10).forEach { c ->
                    val commenter = c.jsonObject["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content
                    val commentBody = c.jsonObject["body"]?.jsonPrimitive?.contentOrNull ?: ""
                    append("**@$commenter:** ${commentBody.take(300)}\n")
                }
            }
        }

        // ── Related PRs ───────────────────────────────────────────────────────
        val relatedPRsSection = if (data.relatedPRs.isNotEmpty()) buildString {
            append("\n## RELATED PRs (same branch `$headRef` in other repos)\n")
            data.relatedPRs.forEach { related ->
                append("### ${related.repo}#${related.number} — ${related.title}\n")
                append("URL: ${related.url}\n")
                if (related.diffSnippet.isNotBlank()) {
                    append("```diff\n${related.diffSnippet}\n```\n")
                }
            }
        } else ""

        // ── Re-review: new commits since the last review ──────────────────────
        val reReviewSection = if (data.previousReview != null) buildString {
            append("\n## PREVIOUS REVIEW (this PR was reviewed before)\n")
            append("**Previous verdict:** ${data.previousReview.verdict}\n")
            append("**Previous review:**\n${data.previousReview.reviewText.take(1000)}\n")
            append("\nThis is a RE-REVIEW. Focus on:\n")
            append("- Whether issues from the previous review were addressed\n")
            append("- New changes introduced since the last review\n")
            append("Total commits in PR: ${data.commitShas.size}\n")
        } else ""

        // ── Repo-specific rules ───────────────────────────────────────────────
        val repoRulesSection = if (data.repoRules != null) buildString {
            append("\n## REPO-SPECIFIC RULES\n")
            append(data.repoRules.take(3000))
        } else ""

        val systemPrompt = ReviewPrompt.system(knowledgeBase, config.reviewLanguage)
        val userPrompt = ReviewPrompt.user(
            title, author, headRef, baseRef, body, fileList, data.diffText, stack,
            existingCommentsSection, relatedPRsSection, reReviewSection, repoRulesSection
        )

        // OAuth (subscription) tokens require the Claude Code identity as the first
        // `system` block or Anthropic rejects the call with a 429; API keys don't.
        val isOAuth = AnthropicAuth.activeSchemeIsOAuth(context)

        val requestBody = buildJsonObject {
            put("model", config.model)
            put("max_tokens", 8192)
            // Stable prefix (instructions + KB + severities + format) in `system`, cached
            // for 1h. The diff and the PR data go in the (volatile) `user` message, so they
            // don't invalidate the cache. Verify hits with usage.cache_read_input_tokens.
            put("system", AnthropicAuth.buildSystemBlocks(isOAuth, systemPrompt))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                }
            }
        }.toString()

        val builder = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
        AnthropicAuth.applyTo(context, builder)

        val response = executeWithRetry(builder.build())
        if (!response.isSuccessful) {
            val err = response.body?.string() ?: ""
            throw Exception("Claude API error ${response.code}: $err")
        }

        val responseObj = json.parseToJsonElement(response.body?.string() ?: "").jsonObject

        // Cache usage log (can be checked in Logcat under the "ReviewManager" tag).
        // cache_read > 0 → the 1h cache was hit; cache_write > 0 → it was written this time.
        responseObj["usage"]?.jsonObject?.let { usage ->
            val cacheRead = usage["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            val cacheWrite = usage["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            val input = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            val output = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            Log.d(
                "ReviewManager",
                "Claude usage → cache_read=$cacheRead cache_write=$cacheWrite input=$input output=$output"
            )
        }

        val rawText = responseObj["content"]
            ?.jsonArray
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.content
            ?: throw Exception("Claude returned an empty response")
        return normalizeReviewText(rawText)
    }

    private fun postReview(pr: PullRequest, reviewText: String, token: String) {
        // Always post as COMMENT — don't block the PR with REQUEST_CHANGES
        val event = if (reviewText.startsWith("APPROVE")) "APPROVE" else "COMMENT"

        val body = buildJsonObject {
            put("body", reviewText)
            put("event", event)
        }.toString()

        val request = Request.Builder()
            .url("https://api.github.com/repos/${pr.owner}/${pr.repo}/pulls/${pr.number}/reviews")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = executeWithRetry(request)
        if (!response.isSuccessful) {
            val err = response.body?.string() ?: ""
            throw Exception("GitHub API error ${response.code}: $err")
        }
    }

    suspend fun approvePR(context: Context, pr: PullRequest) = withContext(Dispatchers.IO) {
        val githubToken = getGithubToken(context)
        submitReviewEvent(pr, githubToken, "APPROVE")
    }

    suspend fun approveAndMergePR(context: Context, pr: PullRequest) = withContext(Dispatchers.IO) {
        val githubToken = getGithubToken(context)
        val mergeMethod = ConfigStore.load(context).mergeMethod
        // We don't block on CI — the user already saw its state in the UI.
        // If GitHub has branch protection that requires CI, it returns its own error.
        submitReviewEvent(pr, githubToken, "APPROVE")
        // GitHub needs a moment to register the approval and recompute the "mergeable"
        // state; merging right away can return 405/409.
        kotlinx.coroutines.delay(2000)
        mergePR(pr, githubToken, mergeMethod)
    }

    private fun submitReviewEvent(pr: PullRequest, token: String, event: String) {
        val body = buildJsonObject { put("event", event) }.toString()

        val request = Request.Builder()
            .url("https://api.github.com/repos/${pr.owner}/${pr.repo}/pulls/${pr.number}/reviews")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = executeWithRetry(request)
        if (!response.isSuccessful) {
            val err = response.body?.string() ?: ""
            if (response.code == 422) throw Exception("You can't approve your own PR")
            if (response.code == 403) throw Exception(
                "The GitHub token is not allowed to approve. It needs 'Pull requests: Read and write' " +
                "(fine-grained token) or the 'repo' scope (classic token). Details: $err"
            )
            throw Exception("GitHub error ${response.code}: $err")
        }
    }

    class MergeConflictException : Exception("merge conflicts")

    suspend fun mergeOnly(context: Context, pr: PullRequest) = withContext(Dispatchers.IO) {
        val githubToken = getGithubToken(context)
        mergePR(pr, githubToken, ConfigStore.load(context).mergeMethod)
    }

    internal fun mergePR(pr: PullRequest, token: String, mergeMethod: String = AppConfig.DEFAULT_MERGE_METHOD) {
        val body = buildJsonObject { put("merge_method", mergeMethod) }.toString()

        // We retry up to 3 times: right after approving, GitHub can take a while to mark
        // the PR as mergeable and return transient 405/409/422s. If the error persists,
        // we propagate GitHub's REAL message rather than guessing the cause.
        repeat(3) { attempt ->
            if (attempt > 0) Thread.sleep(1500)
            val response = client.newCall(
                Request.Builder()
                    .url("https://api.github.com/repos/${pr.owner}/${pr.repo}/pulls/${pr.number}/merge")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .put(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()

            if (response.isSuccessful) return
            val errBody = response.body?.string() ?: ""
            android.util.Log.w("ReviewManager", "merge attempt ${attempt + 1} → ${response.code}: $errBody")

            when (response.code) {
                // 403: the token lacks write permission to merge.
                403 -> throw Exception(
                    "The GitHub token can't merge. It needs 'Contents: Read and write' " +
                    "(fine-grained token) or the 'repo' scope (classic token). Details: $errBody"
                )
                // 405: merge not allowed — the configured merge method (AppConfig.mergeMethod) is
                // disabled on the repo, branch protection lacks enough approvals, or required
                // checks are failing.
                405 -> throw Exception("Merge not allowed (405): $errBody")
                // 409: conflict (or mergeable_state not computed yet) → retry
                409 -> if (attempt == 2) throw MergeConflictException()
                // 422: PR not mergeable / required checks → retry, then report
                422 -> if (attempt == 2) throw Exception("The PR is not mergeable (422): $errBody")
                else -> throw Exception("GitHub error ${response.code}: $errBody")
            }
        }
        throw Exception("Could not merge the PR after 3 attempts")
    }

    /**
     * CI status of the PR's head. Excludes the checks the user configured as ignored
     * ([AppConfig.ignoredChecks]: informational, non-blocking).
     *  - NONE: there are no checks (no CI, or the workflows haven't started yet)
     *  - PENDING: at least one check hasn't finished (queued/in_progress)
     *  - FAILURE: all finished and at least one failed
     *  - SUCCESS: all finished OK
     */
    private fun getCiStatus(pr: PullRequest, token: String, ignoredChecks: List<String> = emptyList()): CiStatus {
        // 1. the PR's head SHA
        val prResponse = executeWithRetry(
            Request.Builder()
                .url("https://api.github.com/repos/${pr.owner}/${pr.repo}/pulls/${pr.number}")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()
        )

        if (!prResponse.isSuccessful) {
            android.util.Log.w("ReviewManager", "CI ${pr.key}: GET pull → ${prResponse.code} (${prResponse.body?.string()?.take(200)}) → UNKNOWN")
            return CiStatus("UNKNOWN")
        }
        val headSha = prResponse.body?.string()?.let {
            json.parseToJsonElement(it).jsonObject["head"]
                ?.jsonObject?.get("sha")?.jsonPrimitive?.content
        } ?: return CiStatus("NONE")

        // 2. the commit's check-runs — paginate because a repo may have >100 checks
        val checkRunsUrl = "https://api.github.com/repos/${pr.owner}/${pr.repo}/commits/$headSha/check-runs?per_page=100"
        val checkRuns: JsonArray = run {
            val combined = mutableListOf<JsonElement>()
            var nextUrl: String? = checkRunsUrl
            while (nextUrl != null) {
                val req = Request.Builder()
                    .url(nextUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .build()
                val resp = executeWithRetry(req)
                if (!resp.isSuccessful) {
                    android.util.Log.w("ReviewManager", "CI ${pr.key}: GET check-runs → ${resp.code} (${resp.body?.string()?.take(200)}) — is the token missing the Checks:Read permission? → UNKNOWN")
                    return CiStatus("UNKNOWN")
                }
                val bodyStr = resp.body?.string() ?: return CiStatus("NONE")
                val page = json.parseToJsonElement(bodyStr).jsonObject["check_runs"]?.jsonArray
                    ?: return CiStatus("NONE")
                combined.addAll(page)
                nextUrl = parseLinkNext(resp.header("Link") ?: "")
            }
            JsonArray(combined)
        }

        // Skip the checks the user marked as informational (Settings → Review).
        val runs = checkRuns.filter { run ->
            val name = run.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: ""
            !isIgnoredCheck(name, ignoredChecks)
        }
        if (runs.isEmpty()) return CiStatus("NONE")

        val pending = runs.filter { run ->
            run.jsonObject["status"]?.jsonPrimitive?.contentOrNull != "completed"
        }.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }

        val failing = runs.filter { run ->
            val conclusion = run.jsonObject["conclusion"]?.jsonPrimitive?.contentOrNull
            conclusion == "failure" || conclusion == "timed_out" ||
                conclusion == "action_required" || conclusion == "cancelled"
        }.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }

        val state = when {
            pending.isNotEmpty() -> "PENDING"
            failing.isNotEmpty() -> "FAILURE"
            else                 -> "SUCCESS"
        }
        return CiStatus(state, failing, pending)
    }

    /** True if [checkName] contains any entry of [ignored] (case-insensitive). Pure → testable. */
    internal fun isIgnoredCheck(checkName: String, ignored: List<String>): Boolean =
        ignored.any { it.isNotBlank() && checkName.contains(it.trim(), ignoreCase = true) }

    /** Public variant that takes a context — used by autonomous mode to wait for CI. */
    suspend fun fetchCiStatus(context: Context, pr: PullRequest): CiStatus = withContext(Dispatchers.IO) {
        val githubToken = getGithubToken(context)
        val ignored = ConfigStore.load(context).ignoredChecks
        runCatching { getCiStatus(pr, githubToken, ignored) }
            .onFailure { android.util.Log.w("ReviewManager", "CI ${pr.key}: fetchCiStatus threw ${it.message} → UNKNOWN") }
            .getOrDefault(CiStatus("UNKNOWN"))
    }

    /** Lightweight mergeability check (with polling) for the manual flow. */
    suspend fun fetchHasConflicts(context: Context, pr: PullRequest): Boolean = withContext(Dispatchers.IO) {
        val token = getGithubToken(context)
        val base = "https://api.github.com/repos/${pr.owner}/${pr.repo}"
        val (mergeable, state) = runCatching { pollMergeable(base, pr.number, token, initial = null) }
            .getOrDefault(null to null)
        MergePolicy.hasConflict(mergeable, state)
    }

    data class PRData(
        val prInfo: JsonObject,
        val diffText: String,
        val files: JsonArray,
        val inlineComments: JsonArray = JsonArray(emptyList()),
        val issueComments: JsonArray = JsonArray(emptyList()),
        val existingReviews: JsonArray = JsonArray(emptyList()),
        val repoRules: String? = null,
        val relatedPRs: List<RelatedPR> = emptyList(),
        val commitShas: List<String> = emptyList(),
        val previousReview: dev.pocketreviewer.data.ReviewEntry? = null,
        // Mergeability (GitHub computes it asynchronously; see pollMergeable). mergeable=null = still computing.
        val mergeable: Boolean? = null,
        val mergeableState: String? = null,   // clean|dirty|behind|blocked|unstable|unknown
        val isDraft: Boolean = false
    )

    data class RelatedPR(
        val repo: String,
        val number: Int,
        val title: String,
        val url: String,
        val diffSnippet: String
    )
}
