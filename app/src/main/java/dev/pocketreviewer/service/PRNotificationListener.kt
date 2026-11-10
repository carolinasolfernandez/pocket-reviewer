package dev.pocketreviewer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dev.pocketreviewer.R
import dev.pocketreviewer.api.ChatContext
import dev.pocketreviewer.api.ChatMessages
import dev.pocketreviewer.config.AppConfig
import dev.pocketreviewer.config.ConfigStore
import dev.pocketreviewer.messaging.Messaging
import dev.pocketreviewer.messaging.SlackNames
import dev.pocketreviewer.service.NotificationSources.Source
import dev.pocketreviewer.util.PrLinks
import dev.pocketreviewer.api.ReviewManager
import dev.pocketreviewer.data.ReviewEntry
import dev.pocketreviewer.data.ReviewHistoryRepository
import dev.pocketreviewer.ui.NotificationActionActivity
import dev.pocketreviewer.util.PullRequest
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.pocketreviewer.api.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

class PRNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // We use the Android notification ID (not the PR key) to avoid processing
    // the SAME notification twice, while still letting the same PR
    // arrive again in a different notification.
    // Insertion-ordered, bounded at SEEN_IDS_CAP — eldest entry evicted first.
    private val seenNotifIds: MutableSet<String> = Collections.synchronizedSet(
        object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                if (contains(element)) return false
                if (size >= SEEN_IDS_CAP) {
                    val eldest = iterator().next()
                    remove(eldest)
                }
                return super.add(element)
            }
        }
    )

    // Groups PRs by message: messageId → { prKeys, eyesReactionName }
    data class MessageGroup(val prKeys: MutableSet<String> = ConcurrentHashMap.newKeySet(), var eyesReactionName: String? = null)
    val messageGroups: MutableMap<String, MessageGroup> = ConcurrentHashMap()

    // threadId → PullRequest — threads where we left the "left comments" reply
    val watchedThreads: MutableMap<String, PullRequest> = ConcurrentHashMap()

    // PRs with an autonomous review IN FLIGHT (prevents concurrent double reviews of the same PR
    // when Chat re-posts the grouped notification). Cleared in invokeOnCompletion.
    private val inFlightAutonomous: MutableSet<String> =
        Collections.synchronizedSet(HashSet<String>())

    // Bases of OUR OWN chat messages (thread_msg / conflicts / approve-failed).
    // They identify one of our own messages when it comes back as a notification so we do NOT
    // re-review the PR whose URL it may embed. Cached by a collector (does not block the thread).
    @Volatile private var ownReplyBases: List<String> = ChatMessages.ownEchoBases(null, null, null)

    // Cached settings flags — updated by a collector in onListenerConnected so that
    // onNotificationPosted never blocks the service main thread with a DataStore read.
    @Volatile private var config: AppConfig = AppConfig()

    companion object {
        var instance: PRNotificationListener? = null
        var lastLog: String = ""
            private set

        fun appendLog(line: String) {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            lastLog = "[$ts] $line\n" + lastLog.lines().take(100).joinToString("\n")
        }
        const val CHANNEL_PENDING = "pr_pending"
        const val CHANNEL_STATUS  = "pr_status"
        const val ACTION_CONFIRM       = "dev.pocketreviewer.CONFIRM_REVIEW"
        const val ACTION_DISMISS       = "dev.pocketreviewer.DISMISS_PR"
        const val ACTION_APPROVE       = "dev.pocketreviewer.APPROVE_PR"
        const val ACTION_APPROVE_MERGE = "dev.pocketreviewer.APPROVE_AND_MERGE_PR"
        const val EXTRA_PR_URL       = "pr_url"
        const val EXTRA_PR_KEY       = "pr_key"
        const val EXTRA_SPACE_NAME    = "chat_space_name"
        const val EXTRA_MESSAGE_NAME  = "chat_message_name"
        const val EXTRA_THREAD_NAME   = "chat_thread_name"
        const val EXTRA_REACTION_NAME = "chat_reaction_name"
        const val EXTRA_MSG_ID         = "chat_msg_id"
        const val EXTRA_IS_MULTI_PR    = "is_multi_pr"
        const val ACTION_REREVIEW      = "dev.pocketreviewer.REREVIEW"
        const val EXTRA_REPLY_TEXT     = "reply_text"
        const val EXTRA_OPEN_PR_KEY    = "open_pr_key"

        private val SEEN_NOTIF_IDS_KEY = stringSetPreferencesKey("seen_notif_ids")
        private const val SEEN_IDS_CAP = 200
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Restore persisted seen-notif-id set so we don't reprocess notifications that
        // were already handled before the listener was killed and rebound.
        scope.launch {
            val persisted = applicationContext.dataStore.data.first()[SEEN_NOTIF_IDS_KEY] ?: emptySet()
            seenNotifIds.addAll(persisted)
        }
        // Collect DataStore settings so onNotificationPosted can read cached flags
        // without ever blocking the main thread.
        scope.launch {
            ConfigStore.flow(applicationContext).collect { config = it }
        }
        scope.launch {
            applicationContext.dataStore.data
                .map { prefs ->
                    ChatMessages.ownEchoBases(
                        prefs[stringPreferencesKey(ChatMessages.KEY_THREAD_MSG)],
                        prefs[stringPreferencesKey(ChatMessages.KEY_CONFLICTS)],
                        prefs[stringPreferencesKey(ChatMessages.KEY_APPROVE_FAILED)]
                    )
                }
                .collect { bases -> ownReplyBases = bases }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel the scope: stops the two infinite DataStore collectors
        // (settings) and any in-flight review, so dead coroutines/instances
        // do not pile up on every listener rebind.
        scope.cancel()
        instance = null
    }

    // Called every time a notification is posted to the system
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val TAG = "PRListener"

        // Snapshot of the user's settings for this notification.
        val cfg = config
        if (!cfg.notificationAnalysis) return

        val extras = sbn.notification.extras
        val pkg    = sbn.packageName
        if (pkg == packageName) return   // never react to our own notifications

        val source = NotificationSources.classify(pkg)
        if (!NotificationSources.isEnabled(source, cfg)) return

        // Google Chat and Slack share the full flow (reactions + thread replies).
        if (source == Source.GOOGLE_CHAT || source == Source.SLACK) {
            // getCharSequence, not getString: chat apps often post styled (Spannable) text,
            // for which getString returns null.
            val title   = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text    = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

            // Also extract grouped messages (NotificationCompat.MessagingStyle)
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            // Log every key of the first bundle to find the message ID
            messages?.firstOrNull()?.let { first ->
                val bundle = first as? android.os.Bundle
                android.util.Log.d(TAG, "FIRST_MSG_BUNDLE keys: ${bundle?.keySet()?.joinToString()}")
                bundle?.keySet()?.forEach { k ->
                    android.util.Log.d(TAG, "  msg[$k] = ${bundle.get(k)}")
                }
            }
            // Log the notification's extras looking for IDs
            listOf("argMessageId","argThreadId","argMessageName","argSpaceId","com.google.android.gms.chat.MESSAGE_ID","com.google.android.gms.chat.THREAD_KEY","com.google.android.gms.chat.SPACE_ID").forEach { k ->
                val v = extras.getString(k)
                if (v != null) android.util.Log.d(TAG, "EXTRA[$k] = $v")
            }
            val msgTexts = messages?.joinToString(" | ") { msg ->
                try {
                    val bundle = msg as? android.os.Bundle
                    bundle?.getCharSequence("text")?.toString() ?: ""
                } catch (e: Exception) { "" }
            } ?: ""
            // Ordered list (oldest→newest) of each grouped message's text, so PRs are
            // extracted ONLY from the just-arrived message (see AutonomousDispatch).
            val messageTextList: List<String> = messages?.mapNotNull { msg ->
                try { (msg as? android.os.Bundle)?.getCharSequence("text")?.toString() } catch (e: Exception) { null }
            } ?: emptyList()

            val allText = "$title $text $bigText $msgTexts"
            android.util.Log.d(TAG, "Chat notif from $pkg | tag=${sbn.tag} | title=$title | text=$text | bigText=${bigText.take(200)} | messages=$msgTexts")
            appendLog("Chat: pkg=$pkg title=$title text=${text.take(100)}")

            // Look for PRs in the whole text, grouped messages included
            val chatContext = extractChatContext(sbn)
            val msgId = chatContext?.messageName ?: chatContext?.threadName
                ?: chatContext?.spaceName?.takeUnless { it == SlackNames.space(null) }
                ?: "no-chat-${sbn.id}"

            // Only listen to the spaces/channels the user picked (empty list = all).
            val conversationLabels = listOf(
                title,
                extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString(),
                chatContext?.spaceName
            )
            if (!NotificationSources.conversationAllowed(NotificationSources.allowlist(source, cfg), conversationLabels)) {
                android.util.Log.d(TAG, "Conversation not in the allowlist, skip: title=$title")
                return
            }

            // Avoid processing the same Android notification twice (Samsung sometimes
            // fires onNotificationPosted several times for the same notification).
            // We use the notification ID (not the PR key) so the same PR
            // in a NEW message is always processed again.
            val notifId = "${sbn.id}_${sbn.postTime}"
            if (seenNotifIds.contains(notifId)) {
                android.util.Log.d(TAG, "Notif $notifId already processed, skip")
                return
            }
            seenNotifIds.add(notifId)
            // Persist the updated set (LRU-bounded LinkedHashSet handles eviction).
            scope.launch {
                val snapshot = synchronized(seenNotifIds) { seenNotifIds.toSet() }
                applicationContext.dataStore.edit { prefs -> prefs[SEEN_NOTIF_IDS_KEY] = snapshot }
            }

            // Extract ONLY from the just-arrived message: Chat groups previous messages into the
            // notification, and scanning everything re-detected the previous message's PR (re-scan).
            val newestText = AutonomousDispatch.newestMessageText(messageTextList, text, bigText)

            // Guard: if the new message is OURS (reply/conflicts/approve-failed),
            // do NOT re-review the PR whose URL it may embed. Matched by the base text.
            if (AutonomousDispatch.isOwnThreadReply(newestText, ownReplyBases)) {
                android.util.Log.d(TAG, "Message is our own thread reply, skip: ${newestText.take(80)}")
                return
            }

            val prsInMessage = PrLinks.find(newestText, cfg.allowedOwners, cfg.ignoredRepos)
                .map { it.url to it.key }

            // Diagnostics: PRs present in the grouped body but NOT in the new message
            // (previous messages re-included by Chat) — we ignore them on purpose.
            val allPrKeys = PrLinks.findAll(allText).map { it.key }.toSet()
            val ignoredOld = allPrKeys - prsInMessage.map { it.second }.toSet()
            if (ignoredOld.isNotEmpty()) android.util.Log.d(TAG, "Ignored PRs from previous messages: $ignoredOld")

            if (prsInMessage.isEmpty() && allText.contains("github.com") && allText.contains("/pull/")) {
                android.util.Log.d(TAG, "PR link ignored by the owner/repo filters: ${allText.take(300)}")
            }

            android.util.Log.d(TAG, "PRs found: ${prsInMessage.size} — ${prsInMessage.map { it.second }}")
            appendLog("PRs found: ${prsInMessage.size}")

            if (prsInMessage.isNotEmpty()) {
                val group = messageGroups.getOrPut(msgId) { MessageGroup() }
                prsInMessage.forEach { (_, key) -> group.prKeys.add(key) }
                val isAutonomous = cfg.autonomous
                if (isAutonomous) {
                    // Load history + decide which PRs to launch in a coroutine (does not
                    // block the listener thread), sharing ONE aggregator per notification so the
                    // configurable "approved" emoji (AppConfig.emojiApprove) lands only when ALL the PRs get merged.
                    scope.launch {
                        val history = ReviewHistoryRepository.load(applicationContext)
                        val toLaunch = prsInMessage.filterNot { (_, key) ->
                            AutonomousDispatch.shouldSkipAutonomousReview(key, history, inFlightAutonomous)
                        }
                        if (toLaunch.isEmpty()) {
                            android.util.Log.d(TAG, "All PRs already merged or in flight, skip: ${prsInMessage.map { it.second }}")
                            return@launch
                        }
                        toLaunch.forEach { (_, key) -> inFlightAutonomous.add(key) }
                        val isMultiPR = toLaunch.size > 1
                        val aggregator = MergeAggregator(toLaunch.map { it.second })
                        toLaunch.forEach { (prUrl, key) ->
                            val parts = key.split("/", "#")
                            val pr = PullRequest(url = prUrl, owner = parts[0], repo = parts[1], number = parts[2].toInt(), key = key)
                            // Every detected PR is recorded in History (PENDING).
                            persistPending(pr, chatContext, isMultiPR, null)
                            runAutonomousReview(pr, chatContext, isMultiPR, aggregator)
                        }
                    }
                } else {
                    val isMultiPR = group.prKeys.size > 1
                    prsInMessage.forEach { (prUrl, key) ->
                        val parts = key.split("/", "#")
                        val pr = PullRequest(url = prUrl, owner = parts[0], repo = parts[1], number = parts[2].toInt(), key = key)
                        // Manual: it stays PENDING in History; the review is triggered from there.
                        persistPending(pr, chatContext, isMultiPR, "REVIEW")
                        postPendingAlert(pr)
                    }
                }
            } else {
                // Detect replies in watched threads
                val threadId = chatContext?.threadName ?: chatContext?.messageName
                val watchedPR = AutonomousDispatch.resolveWatchedPR(threadId, chatContext?.spaceName, watchedThreads)
                if (watchedPR != null) {
                    val author = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: getString(R.string.notif_someone)
                    val replyText = text.ifBlank { bigText }.ifBlank { msgTexts }.ifBlank { title }
                    if (replyText.isNotBlank()) {
                        showThreadReplyNotification(watchedPR, author, replyText, chatContext!!)
                    }
                }
            }
            return
        }

        // Any other app: detection only (no chat context → no reactions or replies).
        val title  = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text   = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()  ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val fullText = "$title $text $bigText"

        val chatContext = extractChatContext(sbn)
        val msgId = chatContext?.messageName ?: chatContext?.threadName ?: chatContext?.spaceName ?: "no-chat-${sbn.id}"

        val notifIdOther = "${sbn.id}_${sbn.postTime}"
        if (seenNotifIds.contains(notifIdOther)) return
        seenNotifIds.add(notifIdOther)
        // Persist the updated set (LRU-bounded LinkedHashSet handles eviction).
        scope.launch {
            val snapshot = synchronized(seenNotifIds) { seenNotifIds.toSet() }
            applicationContext.dataStore.edit { prefs -> prefs[SEEN_NOTIF_IDS_KEY] = snapshot }
        }

        val prsInMessage = PrLinks.find(fullText, cfg.allowedOwners, cfg.ignoredRepos)
            .map { it.url to it.key }

        if (prsInMessage.isEmpty()) return

        // Register the group
        val group = messageGroups.getOrPut(msgId) { MessageGroup() }
        prsInMessage.forEach { (_, key) -> group.prKeys.add(key) }
        val isMultiPR = group.prKeys.size > 1
        val isAutonomousOther = cfg.autonomous

        prsInMessage.forEach { (prUrl, key) ->
            val parts = key.split("/", "#")
            val pr = PullRequest(url = prUrl, owner = parts[0], repo = parts[1], number = parts[2].toInt(), key = key)
            persistPending(pr, chatContext, isMultiPR, if (isAutonomousOther) null else "REVIEW")
            if (isAutonomousOther) {
                // Other apps: one aggregator per PR.
                runAutonomousReview(pr, chatContext, isMultiPR, MergeAggregator(listOf(key)))
            } else {
                // Manual: the review is triggered from History; we only alert.
                postPendingAlert(pr)
            }
        }

    }

    // Extracts space/message/thread from the Google Chat notification
    /** Persists (idempotently) a PENDING entry in History for each detected PR,
     *  keeping the Chat context so the flow can be triggered from History. */
    private fun persistPending(pr: PullRequest, chatContext: ChatContext?, isMultiPR: Boolean, manualStage: String?) {
        scope.launch {
            runCatching {
                ReviewHistoryRepository.ensurePending(applicationContext, ReviewEntry(
                    prKey = pr.key, prUrl = pr.url,
                    verdict = "COMMENT", reviewText = "",
                    timestampMs = System.currentTimeMillis(),
                    status = "PENDING",
                    chatSpaceName = chatContext?.spaceName,
                    chatMessageName = chatContext?.messageName,
                    chatThreadName = chatContext?.threadName,
                    isMultiPR = isMultiPR,
                    manualStage = manualStage
                ))
            }
        }
    }

    /** Manual mode: one tap-to-open alert. The whole flow is driven from History. */
    private fun postPendingAlert(pr: PullRequest) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(this, dev.pocketreviewer.ui.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("open_tab", "history")
            putExtra(EXTRA_OPEN_PR_KEY, pr.key)
        }
        val openPending = PendingIntent.getActivity(
            this, pr.key.hashCode() + 30, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(pr.key.hashCode() + 30,
            NotificationCompat.Builder(this, CHANNEL_PENDING)
                .setSmallIcon(R.drawable.ic_code)
                .setContentTitle(getString(R.string.notif_pr_detected))
                .setContentText(pr.key)
                .setContentIntent(openPending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    private fun extractChatContext(sbn: StatusBarNotification): ChatContext? {
        val extras = sbn.notification.extras
        when (NotificationSources.classify(sbn.packageName)) {
            Source.OTHER -> return null
            Source.SLACK -> {
                // Slack does not document where the channel id lives in a notification, so
                // this is best effort: look for an id-shaped token in the identifiers Android
                // exposes. Without one, SlackClient locates the message with search.messages.
                val channelId = SlackNames.channelIdFrom(listOf(
                    sbn.notification.shortcutId, sbn.tag, sbn.key, sbn.groupKey,
                    sbn.notification.group
                ))
                android.util.Log.d("PRListener", "Slack notif channelId=${channelId ?: "unknown"} tag=${sbn.tag}")
                return ChatContext(SlackNames.space(channelId), null, null)
            }
            Source.GOOGLE_CHAT -> { /* handled below */ }
        }

        // Google Chat includes the space in the notification tag
        val tag = sbn.tag ?: ""
        android.util.Log.d("PRListener", "extractChatContext tag=$tag extras keys=${extras.keySet()?.joinToString()}")

        // New format (unified Gmail/Chat): "ACCOUNT::a:TYPE:/chime/space/SPACE_ID"
        val chimeMatch = Regex("/chime/space/([^/\\s]+)").find(tag)
        if (chimeMatch != null) {
            val spaceId   = chimeMatch.groupValues[1]
            val spaceName = "spaces/$spaceId"
            // The Android notification integer id (sbn.id) is NOT a Chat message resource id.
            // Fabricating "spaces/$spaceId/messages/${sbn.id}" produces a bogus name that
            // causes NOT_FOUND errors when reactions are attempted. Leave it null so the
            // downstream ChatContext / reaction logic skips gracefully.
            val messageName: String? = null
            val threadKey = extras.getString("com.google.android.gms.chat.THREAD_KEY")
            val threadName = threadKey?.let { "spaces/$spaceId/threads/$it" }
            return ChatContext(spaceName, messageName, threadName)
        }

        // Classic format: "spaces/SPACE_ID/messages/MSG_ID"
        val tagMatch = Regex("(spaces/[^/]+)/messages/([^/]+)").find(tag)
        if (tagMatch != null) {
            val spaceName   = tagMatch.groupValues[1]
            val messageName = "${tagMatch.groupValues[1]}/messages/${tagMatch.groupValues[2]}"
            val threadName = extras.getString("com.google.android.gms.chat.THREAD_KEY")
                ?.let { "$spaceName/threads/$it" }
            return ChatContext(spaceName, messageName, threadName)
        }

        // Fallback: look in the notification extras
        val spaceId   = extras.getString("com.google.android.gms.chat.SPACE_ID")
        val messageId = extras.getString("com.google.android.gms.chat.MESSAGE_ID")
            ?: extras.getString("android.remote_input.results_key")
        val threadKey = extras.getString("com.google.android.gms.chat.THREAD_KEY")

        if (spaceId != null && messageId != null) {
            val spaceName   = "spaces/$spaceId"
            val messageName = "spaces/$spaceId/messages/$messageId"
            val threadName  = threadKey?.let { "spaces/$spaceId/threads/$it" }
            return ChatContext(spaceName, messageName, threadName)
        }

        // If we cannot extract the IDs, return null (the reviewer still works, just without a reaction)
        return null
    }

    fun runReview(pr: PullRequest) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Progress notification
        val progressNotif = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_code)
            .setContentTitle(getString(R.string.notif_running_review))
            .setContentText(pr.key)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        nm.notify(pr.key.hashCode() + 100, progressNotif)

        scope.launch {
            try {
                ReviewManager.runReview(applicationContext, pr)

                nm.cancel(pr.key.hashCode() + 100)
                nm.notify(pr.key.hashCode() + 200,
                    NotificationCompat.Builder(this@PRNotificationListener, CHANNEL_STATUS)
                        .setSmallIcon(R.drawable.ic_check)
                        .setContentTitle(getString(R.string.notif_review_posted))
                        .setContentText(pr.key)
                        .setAutoCancel(true)
                        .build()
                )
            } catch (e: Exception) {
                nm.cancel(pr.key.hashCode() + 100)
                nm.notify(pr.key.hashCode() + 300,
                    NotificationCompat.Builder(this@PRNotificationListener, CHANNEL_STATUS)
                        .setSmallIcon(R.drawable.ic_dismiss)
                        .setContentTitle(getString(R.string.notif_review_error_title))
                        .setContentText(e.message ?: getString(R.string.notif_unknown_error))
                        .setAutoCancel(true)
                        .build()
                )
            }
        }
    }

    private fun progressNotification(title: String, prKey: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_code)
            .setContentTitle(title)
            .setContentText(prKey)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** Autonomous mode: runs the review in the background with no user intervention. */
    private fun runAutonomousReview(
        pr: PullRequest,
        chatContext: ChatContext?,
        isMultiPR: Boolean,
        aggregator: MergeAggregator
    ) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Keep the process alive for the WHOLE review (initial delay + review + CI wait,
        // up to ~22 min) with a foreground service, so it survives closing/swiping away
        // the app. Best-effort: without the battery exemption, Android 12+ blocks the background
        // start and we carry on as before (in the listener scope).
        ReviewKeepAliveService.acquire(applicationContext)
        scope.launch {
            // Random delay (Settings → Review) between the notification and the review.
            val cfg = config
            val delayMs = if (cfg.delayMaxSec > cfg.delayMinSec)
                kotlin.random.Random.nextLong(cfg.delayMinSec * 1000L, cfg.delayMaxSec * 1000L)
            else cfg.delayMinSec * 1000L
            android.util.Log.d("PRListener", "Autonomous mode: waiting ${delayMs / 1000}s before reviewing ${pr.key}")
            kotlinx.coroutines.delay(delayMs)

            // Review progress notification (ongoing, cannot be dismissed)
            nm.notify(pr.key.hashCode() + 500, progressNotification(getString(R.string.notif_reviewing_pr), pr.key))

            // Step 1: 👀 reaction + review + (post comments + reply if there are issues)
            val result = try {
                ReviewManager.runReviewFull(applicationContext, pr, chatContext, isMultiPR)
            } catch (e: Exception) {
                aggregator.record(pr.key, merged = false)
                nm.cancel(pr.key.hashCode() + 500)
                nm.notify(pr.key.hashCode() + 600,
                    NotificationCompat.Builder(this@PRNotificationListener, CHANNEL_STATUS)
                        .setSmallIcon(R.drawable.ic_dismiss)
                        .setContentTitle(getString(R.string.notif_review_failed))
                        .setContentText("${pr.key}: ${e.message?.take(100) ?: getString(R.string.notif_error_title)}")
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build()
                )
                return@launch
            }

            // Step 2: if the PR is clean (APPROVE), approve and — only if CI passes — merge.
            // If there are issues (COMMENT/REQUEST_CHANGES) the comments were already posted: no merge.
            if (result.verdict == "APPROVE" && cfg.autoApprove) {
                // Gate: if there are merge conflicts → do NOT approve or merge, just flag.
                if (result.hasConflicts) {
                    aggregator.record(pr.key, merged = false)
                    nm.cancel(pr.key.hashCode() + 500)
                    nm.notify(pr.key.hashCode() + 550, statusNotification(
                        getString(R.string.notif_not_approved_conflicts), pr.key))
                    if (chatContext != null) runCatching {
                        ChatMessages.conflicts(applicationContext)?.let { msg ->
                            Messaging.reply(applicationContext, result.resolvedMessageName, chatContext, msg)
                        }
                    }
                    return@launch
                }

                // Wait for CI to finish (capped at 15 min) BEFORE approving: red CI blocks.
                nm.notify(pr.key.hashCode() + 500, progressNotification(getString(R.string.notif_checking_ci), pr.key))
                var ci = runCatching { ReviewManager.fetchCiStatus(applicationContext, pr) }
                    .getOrDefault(ReviewManager.CiStatus("UNKNOWN"))
                if (ci.state == "PENDING") {
                    val maxWaitMs = cfg.ciWaitMinutes * 60 * 1000L; val pollMs = 30_000L; var waited = 0L
                    while (ci.state == "PENDING" && waited < maxWaitMs) {
                        kotlinx.coroutines.delay(pollMs); waited += pollMs
                        ci = runCatching { ReviewManager.fetchCiStatus(applicationContext, pr) }
                            .getOrDefault(ReviewManager.CiStatus("UNKNOWN"))
                    }
                }

                // Red CI → do NOT approve or merge, just flag.
                if (ci.state == "FAILURE") {
                    aggregator.record(pr.key, merged = false)
                    nm.cancel(pr.key.hashCode() + 500)
                    val checks = ci.failingChecks.take(3).joinToString(", ")
                    // Red CI is reported ONLY through the app notification — never to the chat.
                    nm.notify(pr.key.hashCode() + 550, statusNotification(
                        if (checks.isNotBlank()) getString(R.string.notif_not_approved_ci_failed_checks, checks)
                        else getString(R.string.notif_not_approved_ci_failed), pr.key))
                    return@launch
                }

                // Clean (no conflicts, CI not red) → approve.
                nm.notify(pr.key.hashCode() + 500, progressNotification(getString(R.string.notif_approving), pr.key))
                val approveError = runCatching { ReviewManager.approvePR(applicationContext, pr) }
                    .exceptionOrNull()
                if (approveError != null) {
                    // We do not swallow the failure: without approval we do NOT go on to merge.
                    android.util.Log.w("PRListener", "approve ${pr.key} failed: ${approveError.message}")
                    aggregator.record(pr.key, merged = false)
                    nm.cancel(pr.key.hashCode() + 500)
                    nm.notify(pr.key.hashCode() + 550, statusNotification(
                        getString(R.string.notif_approve_failed), pr.key))
                    if (chatContext != null) runCatching {
                        ChatMessages.approveFailed(applicationContext, approveError.message)?.let { msg ->
                            Messaging.reply(applicationContext, result.resolvedMessageName, chatContext, msg)
                        }
                    }
                    return@launch
                }
                ReviewHistoryRepository.updateLatestByKey(applicationContext, pr.key) {
                    it.copy(wasApproved = true)
                }
                nm.cancel(pr.key.hashCode() + 500)

                // We merge if CI passed (SUCCESS), there are no checks (NONE) or it could NOT be read
                // (UNKNOWN): the user asked to merge anyway even when the CI result is
                // unknown. GitHub remains the final gate (branch protection rejects with
                // 405/422 if required checks are missing). FAILURE already bailed out above; only PENDING
                // (checks still running after the 15 min cap) stays unmerged.
                if (!cfg.autoMerge) {
                    // Auto-merge is off: stop after approving.
                    aggregator.record(pr.key, merged = false)
                    nm.notify(pr.key.hashCode() + 550, statusNotification(getString(R.string.notif_approved), pr.key))
                } else if (ci.state == "SUCCESS" || ci.state == "NONE" || ci.state == "UNKNOWN") {
                    var conflictAtMerge = false
                    val merged = try {
                        ReviewManager.mergeOnly(applicationContext, pr); true
                    } catch (e: ReviewManager.MergeConflictException) {
                        conflictAtMerge = true
                        android.util.Log.w("PRListener", "merge ${pr.key}: conflicts detected while merging")
                        false
                    } catch (e: Exception) {
                        android.util.Log.w("PRListener", "merge ${pr.key} failed: ${e.message}"); false
                    }
                    if (merged) {
                        ReviewHistoryRepository.updateLatestByKey(applicationContext, pr.key) {
                            it.copy(wasMerged = true)
                        }
                    }
                    if (!merged && conflictAtMerge && chatContext != null) runCatching {
                        ChatMessages.conflicts(applicationContext)?.let { msg ->
                            Messaging.reply(applicationContext, result.resolvedMessageName, chatContext, msg)
                        }
                    }
                    // Record the outcome; the configurable "approved" emoji (AppConfig.emojiApprove) lands ONLY when
                    // ALL the PRs in the message were merged (exactly once, on the last report).
                    if (aggregator.record(pr.key, merged) == MergeAggregator.Completion.ALL_MERGED && chatContext != null) {
                        runCatching {
                            // The unified Gmail/Chat format carries messageName=null; we use the
                            // real message resolved by the 👀 reaction (same as the manual flow),
                            // otherwise we fall back to the notification's messageName.
                            val target = ManualFlow.reactionTarget(result.resolvedMessageName, chatContext.messageName)
                            if (target != null) {
                                // Replace the working reaction with the configured "approved" emoji.
                                ReviewManager.swapReaction(applicationContext, target, null, "APPROVE")
                            }
                        }
                    }
                    nm.notify(pr.key.hashCode() + 550, statusNotification(
                        when {
                            merged          -> getString(R.string.notif_approved_merged)
                            conflictAtMerge -> getString(R.string.notif_approved_conflicts_not_merged)
                            else            -> getString(R.string.notif_approved_merge_failed)
                        }, pr.key))
                } else {
                    aggregator.record(pr.key, merged = false)
                    nm.notify(pr.key.hashCode() + 550, statusNotification(
                        getString(R.string.notif_approved_ci_not_merged, ci.state), pr.key))
                }
            } else {
                aggregator.record(pr.key, merged = false)
                nm.cancel(pr.key.hashCode() + 500)
                showAutonomousResultNotification(pr, result)
            }
        }.invokeOnCompletion {
            // Any uncovered exit (cancellation, exception) counts as not-merged
            // so the aggregator can complete; record is idempotent (first one wins).
            aggregator.record(pr.key, merged = false)
            inFlightAutonomous.remove(pr.key)
            // We always release the keep-alive (success, error, return@launch or cancellation).
            ReviewKeepAliveService.release(applicationContext)
        }
    }

    /** Simple (auto-cancel) status notification that opens the history. */
    private fun statusNotification(title: String, prKey: String): Notification {
        val openAppIntent = Intent(this, dev.pocketreviewer.ui.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("open_tab", "history")
        }
        val openAppPending = PendingIntent.getActivity(
            this, (prKey + title).hashCode(), openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(title)
            .setContentText(prKey)
            .setContentIntent(openAppPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun showAutonomousResultNotification(pr: PullRequest, result: ReviewManager.ReviewResult) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val title = when (result.verdict) {
            "APPROVE"         -> getString(R.string.notif_review_ok)
            "REQUEST_CHANGES" -> getString(R.string.notif_review_issues_found)
            else              -> getString(R.string.notif_review_comments_left)
        }

        val ciText = when {
            result.ciFailingChecks.isNotEmpty() ->
                " · CI ⚠️: ${result.ciFailingChecks.take(2).joinToString(", ")}${if (result.ciFailingChecks.size > 2) "…" else ""}"
            result.ciState == "PENDING" -> " · ${getString(R.string.notif_ci_in_progress)}"
            result.ciState == "NONE"    -> " · ${getString(R.string.notif_ci_none)}"
            else -> ""
        }

        val openAppIntent = Intent(this, dev.pocketreviewer.ui.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("open_tab", "history")
        }
        val openAppPending = PendingIntent.getActivity(
            this, pr.key.hashCode() + 550,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        nm.notify(pr.key.hashCode() + 550,
            NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(if (result.verdict == "APPROVE") R.drawable.ic_check else R.drawable.ic_code)
                .setContentTitle(title)
                .setContentText("${pr.key}$ciText")
                .setContentIntent(openAppPending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_PENDING, getString(R.string.channel_pending_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = getString(R.string.channel_pending_desc) })

        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_STATUS, getString(R.string.channel_status_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = getString(R.string.channel_status_desc) })
    }

    private fun showThreadReplyNotification(pr: PullRequest, author: String, replyText: String, chatContext: ChatContext) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val reviewIntent = Intent(this, NotificationActionActivity::class.java).apply {
            action = ACTION_REREVIEW
            putExtra(EXTRA_PR_KEY, pr.key)
            putExtra(EXTRA_PR_URL, pr.url)
            putExtra(EXTRA_SPACE_NAME,   chatContext.spaceName)
            putExtra(EXTRA_MESSAGE_NAME, chatContext.messageName)
            chatContext.threadName?.let { putExtra(EXTRA_THREAD_NAME, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val reviewPending = PendingIntent.getActivity(
            this, pr.key.hashCode() + 20, reviewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, NotificationActionActivity::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_PR_KEY, pr.key)
            putExtra(EXTRA_PR_URL, pr.url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val dismissPending = PendingIntent.getActivity(
            this, pr.key.hashCode() + 21, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val shortText = if (replyText.length > 80) replyText.take(80) + "…" else replyText

        nm.notify(pr.key.hashCode() + 700,
            NotificationCompat.Builder(this, CHANNEL_PENDING)
                .setSmallIcon(R.drawable.ic_code)
                .setContentTitle(getString(R.string.notif_thread_reply_title, author, pr.key))
                .setContentText("\"$shortText\"")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("\"$replyText\""))
                .addAction(R.drawable.ic_code, getString(R.string.notif_action_rereview), reviewPending)
                .addAction(R.drawable.ic_dismiss, getString(R.string.notif_action_ignore), dismissPending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    fun showReviewDoneNotification(
        pr: PullRequest,
        verdict: String = "APPROVE",
        chatContext: ChatContext? = null,
        resolvedMessageName: String? = null,
    ) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Chat extras so ApproveService can react (configurable "approved" emoji, AppConfig.emojiApprove) when
        // approving/merging from the notification. We use the RESOLVED (chime-safe) message as messageName.
        fun Intent.putChatExtras() {
            chatContext?.spaceName?.let { putExtra(EXTRA_SPACE_NAME, it) }
            (resolvedMessageName ?: chatContext?.messageName)?.let { putExtra(EXTRA_MESSAGE_NAME, it) }
            chatContext?.threadName?.let { putExtra(EXTRA_THREAD_NAME, it) }
        }

        val approveIntent = Intent(this, NotificationActionActivity::class.java).apply {
            action = ACTION_APPROVE
            putExtra(EXTRA_PR_KEY, pr.key)
            putExtra(EXTRA_PR_URL, pr.url)
            putChatExtras()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val approvePending = PendingIntent.getActivity(
            this, pr.key.hashCode() + 10,
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mergeIntent = Intent(this, NotificationActionActivity::class.java).apply {
            action = ACTION_APPROVE_MERGE
            putExtra(EXTRA_PR_KEY, pr.key)
            putExtra(EXTRA_PR_URL, pr.url)
            putChatExtras()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val mergePending = PendingIntent.getActivity(
            this, pr.key.hashCode() + 11,
            mergeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        nm.notify(pr.key.hashCode() + 200,
            NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_check)
                .setContentTitle(if (verdict == "REQUEST_CHANGES") getString(R.string.notif_review_with_issues) else getString(R.string.notif_review_ok))
                .setContentText(pr.key)
                .addAction(R.drawable.ic_check, getString(R.string.notif_action_approve), approvePending)
                .addAction(R.drawable.ic_code, getString(R.string.notif_action_approve_merge), mergePending)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

}
