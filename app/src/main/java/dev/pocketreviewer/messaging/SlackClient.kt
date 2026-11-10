package dev.pocketreviewer.messaging

import android.content.Context
import android.util.Log
import dev.pocketreviewer.api.ChatContext
import dev.pocketreviewer.config.Keys
import dev.pocketreviewer.config.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Builds and parses the resource names used for Slack (see [MessagingProvider]):
 *
 *   space    "slack/<channelId>"            ("slack/unknown" when the notification had no id)
 *   message  "slack/<channelId>/messages/<ts>"
 *   thread   "slack/<channelId>/threads/<threadTs>"
 *   reaction "slack/<channelId>/messages/<ts>/reactions/<emojiName>"
 *
 * Pure → unit-testable.
 */
object SlackNames {
    const val ROOT = "slack"
    const val UNKNOWN_CHANNEL = "unknown"

    private val MESSAGE = Regex("^slack/([^/]+)/messages/([^/]+)$")
    private val REACTION = Regex("^slack/([^/]+)/messages/([^/]+)/reactions/([^/]+)$")
    private val THREAD = Regex("^slack/([^/]+)/threads/([^/]+)$")
    private val SPACE = Regex("^slack/([^/]+)$")
    // Slack conversation ids: C… (channel), G… (private/group), D… (direct message).
    private val CHANNEL_ID = Regex("(?<![A-Za-z0-9])([CGD][A-Z0-9]{8,12})(?![A-Za-z0-9])")

    data class MessageRef(val channel: String, val ts: String)
    data class ReactionRef(val channel: String, val ts: String, val emoji: String)

    fun owns(name: String): Boolean = name == ROOT || name.startsWith("$ROOT/")

    fun space(channelId: String?): String = "$ROOT/${channelId?.takeIf { it.isNotBlank() } ?: UNKNOWN_CHANNEL}"
    fun message(channelId: String, ts: String): String = "$ROOT/$channelId/messages/$ts"
    fun thread(channelId: String, threadTs: String): String = "$ROOT/$channelId/threads/$threadTs"
    fun reaction(channelId: String, ts: String, emoji: String): String =
        "${message(channelId, ts)}/reactions/$emoji"

    fun parseMessage(name: String): MessageRef? =
        MESSAGE.find(name.trim())?.let { MessageRef(it.groupValues[1], it.groupValues[2]) }

    fun parseReaction(name: String): ReactionRef? =
        REACTION.find(name.trim())?.let { ReactionRef(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }

    /** Thread ts of a thread name, or null. */
    fun parseThreadTs(name: String): String? = THREAD.find(name.trim())?.groupValues?.get(2)

    /** Channel id of a space name, or null when it is unknown / not a Slack space. */
    fun channelOfSpace(name: String?): String? =
        name?.let { SPACE.find(it.trim())?.groupValues?.get(1) }?.takeIf { it != UNKNOWN_CHANNEL }

    /**
     * Best-effort channel id from the identifiers Android exposes for a Slack
     * notification (shortcut id, tag, key, group key). Returns the first id-looking
     * token, or null — in which case the message is located by search instead.
     */
    fun channelIdFrom(candidates: List<String?>): String? =
        candidates.asSequence().filterNotNull()
            .mapNotNull { CHANNEL_ID.find(it)?.groupValues?.get(1) }
            .firstOrNull()
}

/**
 * Slack Web API client. Authenticates with a **user token** (`xoxp-…`) that the user
 * creates for their own Slack app and pastes in Settings — reactions and replies are
 * then made as that user, exactly like the Google Chat integration.
 *
 * Required user scopes: `reactions:write`, `chat:write`, `search:read`, and the
 * `*:history` scopes for the conversation types you use (`channels:history`,
 * `groups:history`, `im:history`, `mpim:history`). See docs/SLACK.md.
 *
 * Without a token the app still detects PR links in Slack notifications and reviews
 * them; it just doesn't react or reply in Slack.
 */
object SlackClient : MessagingProvider {

    private const val TAG = "SlackClient"
    private const val API = "https://slack.com/api"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun owns(resourceName: String): Boolean = SlackNames.owns(resourceName)

    private suspend fun token(context: Context): String? = SecretStore.get(context, Keys.SLACK_TOKEN)

    override suspend fun isConnected(context: Context): Boolean = token(context) != null

    /** Calls a Web API method. Returns the parsed body; `ok=false` bodies are returned too. */
    private fun call(token: String, method: String, params: Map<String, String>): JSONObject {
        val form = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val resp = client.newCall(
            Request.Builder().url("$API/$method")
                .addHeader("Authorization", "Bearer $token")
                .post(form)
                .build()
        ).execute()
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            Log.w(TAG, "$method → HTTP ${resp.code}")
            return JSONObject().put("ok", false).put("error", "http_${resp.code}")
        }
        return runCatching { JSONObject(body) }
            .getOrElse { JSONObject().put("ok", false).put("error", "invalid_json") }
    }

    /** Verifies [token] with `auth.test`. Returns the workspace/user label, or throws with Slack's error. */
    suspend fun testToken(token: String): String = withContext(Dispatchers.IO) {
        val r = call(token, "auth.test", emptyMap())
        if (!r.optBoolean("ok")) throw Exception(r.optString("error", "unknown_error"))
        "${r.optString("user")} @ ${r.optString("team")}"
    }

    /** Result of `auth.test` for the stored token: true/false, or null when no token is stored. */
    suspend fun testConnection(context: Context): Boolean? {
        val t = token(context) ?: return null
        return runCatching { testToken(t) }.isSuccess
    }

    /**
     * Finds the message that contains [prUrl].
     *  1. If the channel is known, scan its recent history (cheap, no search scope needed).
     *  2. Otherwise — or if it wasn't there, e.g. the link was posted inside a thread —
     *     use `search.messages`, newest first.
     */
    private fun findMessage(token: String, prUrl: String, hintChannel: String?): SlackNames.MessageRef? {
        if (hintChannel != null) {
            val r = call(token, "conversations.history", mapOf("channel" to hintChannel, "limit" to "50"))
            if (r.optBoolean("ok")) {
                val messages = r.optJSONArray("messages")
                for (i in 0 until (messages?.length() ?: 0)) {
                    val msg = messages!!.getJSONObject(i)
                    // org.json escapes "/" as "\/" when serializing; normalize before matching.
                    if (msg.toString().replace("\\/", "/").contains(prUrl)) {
                        val ts = msg.optString("ts").takeIf { it.isNotEmpty() } ?: continue
                        return SlackNames.MessageRef(hintChannel, ts)
                    }
                }
            } else {
                Log.w(TAG, "conversations.history($hintChannel) → ${r.optString("error")}")
            }
        }
        val s = call(token, "search.messages", mapOf(
            "query" to "\"$prUrl\"", "sort" to "timestamp", "sort_dir" to "desc", "count" to "5"
        ))
        if (!s.optBoolean("ok")) {
            Log.w(TAG, "search.messages → ${s.optString("error")}")
            return null
        }
        val matches = s.optJSONObject("messages")?.optJSONArray("matches") ?: return null
        for (i in 0 until matches.length()) {
            val m = matches.getJSONObject(i)
            val channel = m.optJSONObject("channel")?.optString("id").orEmpty()
            val ts = m.optString("ts")
            if (channel.isNotEmpty() && ts.isNotEmpty()) return SlackNames.MessageRef(channel, ts)
        }
        return null
    }

    override suspend fun reactToPrMessage(
        context: Context, prUrl: String, hintSpaceName: String?, emojiSpec: String
    ): String? {
        val token = token(context) ?: run {
            Log.d(TAG, "No Slack token — skipping reaction")
            return null
        }
        return withContext(Dispatchers.IO) {
            val ref = runCatching { findMessage(token, prUrl, SlackNames.channelOfSpace(hintSpaceName)) }
                .onFailure { Log.w(TAG, "findMessage failed: ${it.message}") }
                .getOrNull() ?: return@withContext null
            val messageName = SlackNames.message(ref.channel, ref.ts)
            // The message is resolved: never return null because the reaction failed,
            // or every later step (verdict swap, thread reply) would lose its target.
            addReaction(token, ref, emojiSpec) ?: messageName
        }
    }

    /** Adds the reaction; `already_reacted` counts as success (same handle). */
    private fun addReaction(token: String, ref: SlackNames.MessageRef, emojiSpec: String): String? {
        val name = EmojiSpec.toSlackName(emojiSpec) ?: run {
            Log.w(TAG, "No Slack name for emoji '$emojiSpec' — use a shortcode such as 'eyes'")
            return null
        }
        val r = call(token, "reactions.add", mapOf("channel" to ref.channel, "timestamp" to ref.ts, "name" to name))
        val error = r.optString("error")
        return if (r.optBoolean("ok") || error == "already_reacted") {
            SlackNames.reaction(ref.channel, ref.ts, name)
        } else {
            Log.w(TAG, "reactions.add($name) → $error")
            null
        }
    }

    override suspend fun react(context: Context, messageName: String, emojiSpec: String): String? {
        val token = token(context) ?: return null
        val ref = SlackNames.parseMessage(messageName) ?: return null
        return withContext(Dispatchers.IO) { runCatching { addReaction(token, ref, emojiSpec) }.getOrNull() }
    }

    override suspend fun removeReaction(context: Context, reactionName: String) {
        val token = token(context) ?: return
        val ref = SlackNames.parseReaction(reactionName) ?: return
        withContext(Dispatchers.IO) {
            val r = call(token, "reactions.remove", mapOf(
                "channel" to ref.channel, "timestamp" to ref.ts, "name" to ref.emoji
            ))
            if (!r.optBoolean("ok") && r.optString("error") != "no_reaction") {
                Log.w(TAG, "reactions.remove(${ref.emoji}) → ${r.optString("error")}")
            }
        }
    }

    /**
     * ts of the thread [ref] belongs to. A reply must be posted with the PARENT's ts,
     * so if the PR message is itself a threaded reply we look its parent up.
     */
    private fun threadRootOf(token: String, ref: SlackNames.MessageRef): String {
        val r = call(token, "conversations.replies", mapOf(
            "channel" to ref.channel, "ts" to ref.ts, "limit" to "1", "inclusive" to "true"
        ))
        val first = r.optJSONArray("messages")?.optJSONObject(0)
        return first?.optString("thread_ts")?.takeIf { it.isNotEmpty() } ?: ref.ts
    }

    override suspend fun reply(
        context: Context, resolvedMessageName: String?, chatContext: ChatContext, text: String
    ): Boolean {
        val token = token(context) ?: return false
        return withContext(Dispatchers.IO) {
            val ref = (resolvedMessageName ?: chatContext.messageName)?.let { SlackNames.parseMessage(it) }
            val channel = ref?.channel ?: SlackNames.channelOfSpace(chatContext.spaceName)
            if (channel == null) {
                Log.w(TAG, "reply: unknown channel, cannot post")
                return@withContext false
            }
            val threadTs = when {
                ref != null -> runCatching { threadRootOf(token, ref) }.getOrDefault(ref.ts)
                else -> chatContext.threadName?.let { SlackNames.parseThreadTs(it) }
            }
            val params = buildMap {
                put("channel", channel)
                put("text", text)
                threadTs?.let { put("thread_ts", it) }
            }
            val r = runCatching { call(token, "chat.postMessage", params) }.getOrNull()
            val ok = r?.optBoolean("ok") == true
            if (!ok) Log.w(TAG, "chat.postMessage → ${r?.optString("error")}")
            ok
        }
    }
}
