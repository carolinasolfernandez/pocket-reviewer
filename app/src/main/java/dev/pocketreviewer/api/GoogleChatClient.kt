package dev.pocketreviewer.api

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import dev.pocketreviewer.messaging.EmojiSpec
import dev.pocketreviewer.messaging.MessagingProvider
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ChatContext(
    val spaceName: String,     // "spaces/SPACE_ID"
    val messageName: String?,  // "spaces/SPACE_ID/messages/MESSAGE_ID", or null when it cannot be determined
    val threadName: String?    // "spaces/SPACE_ID/threads/THREAD_ID"
)

object GoogleChatClient : MessagingProvider {

    private val TAG = "GoogleChatClient"

    // CORE scopes — the minimum needed to react and reply. Whether the app counts as
    // "connected" depends on these. If they fail, there are no reactions.
    // - chat.messages: read (messages.list) and post reactions/replies
    // - chat.messages.reactions.create: create reactions
    // - chat.spaces.readonly: list the user's spaces (spaces.list) to find the
    //   message, because the space ID in the notification (chime ID) does NOT
    //   match the resource name of the Chat REST API.
    val REQUIRED_SCOPES = listOf(
        "https://www.googleapis.com/auth/chat.messages",
        "https://www.googleapis.com/auth/chat.messages.reactions.create",
        "https://www.googleapis.com/auth/chat.spaces.readonly"
    )

    // Optional EXTRA scope — only to read the workspace's custom emojis.
    // It is best-effort: if it isn't granted, we fall back to unicode emojis.
    // IMPORTANT: it is NOT included in the core token because if this scope is not
    // enabled on the GCP consent screen, it would break the WHOLE getToken call.
    private const val CUSTOM_EMOJI_SCOPE =
        "https://www.googleapis.com/auth/chat.customemojis.readonly"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Converts a hex codepoint (e.g. "1f440") to the actual unicode character (👀).
     * The Chat API expects the real emoji in the `unicode` field, NOT the hex code.
     */
    private fun emojiFromHex(hex: String): String = String(Character.toChars(hex.toInt(16)))

    // In-memory cache of shortCode → resource name ("customEmojis/…") for custom emojis.
    // Avoids re-listing the workspace's custom emojis on every review. Filled on first use.
    // NOTE: the resource NAME is cached, not the uid — reacting requires the name (uid is output-only).
    private val customEmojiNameCache = ConcurrentHashMap<String, String>()

    /** Invalidates the cached resource name for [shortCode] (e.g. if the reaction failed). */
    fun invalidateCustomEmoji(shortCode: String) {
        customEmojiNameCache.remove(shortCode.trim(':').lowercase())
    }

    // Get the user's OAuth token. By default it only requests the core scopes
    // (the reliable ones). [scopes] allows requesting extra scopes for specific cases.
    suspend fun getAccessToken(context: Context, scopes: List<String> = REQUIRED_SCOPES): String? {
        return withContext(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(context)
                    ?: return@withContext null
                val scopeString = scopes.joinToString(" ")
                com.google.android.gms.auth.GoogleAuthUtil.getToken(
                    context,
                    account.account!!,
                    "oauth2:$scopeString"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error getting token (${scopes.size} scopes): ${e::class.simpleName}: ${e.message}")
                null
            }
        }
    }

    // Check whether the user has Google Sign-In with the required scopes (core only)
    fun hasRequiredScopes(context: Context): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return REQUIRED_SCOPES.all { scope ->
            GoogleSignIn.hasPermissions(account, Scope(scope))
        }
    }

    // Build the GoogleSignInOptions. We request core + custom emoji: the custom
    // emoji scope is optional (best-effort), but we ask for it so it can be used if
    // the user grants it. Being connected does NOT depend on it (see hasRequiredScopes).
    fun buildSignInOptions(): GoogleSignInOptions {
        val allScopes = (REQUIRED_SCOPES + CUSTOM_EMOJI_SCOPE).map { Scope(it) }
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(allScopes[0], *allScopes.drop(1).toTypedArray())
            .build()
    }

    /**
     * Puts the working emoji (👀 by default) on the Google Chat message that contains [prUrl].
     * If we have the space as a hint, it is tried first. If that fails or there is no
     * hint, it searches ALL the user's spaces (works even if the notification carried
     * no Chat metadata).
     *
     * Required scopes: chat.messages (enough for spaces.list + messages.list + reactions.create).
     */
    suspend fun addEyesReactionForPR(
        context: Context,
        prUrl: String,
        hintSpaceName: String? = null,
        workingEmoji: String = "👀"  // 👀 by default
    ): String? {
        val token = getAccessToken(context) ?: run {
            Log.w(TAG, "No Google token — skipping reaction")
            return null
        }

        return withContext(Dispatchers.IO) {
            // 1. Try the known space first (faster)
            if (hintSpaceName != null) {
                Log.d(TAG, "Trying reaction in hint space: $hintSpaceName")
                val result = tryReactionInSpace(token, hintSpaceName, prUrl, workingEmoji)
                if (result != null) return@withContext result
                Log.d(TAG, "Not found in hint, searching all spaces...")
            }

            // 2. List all the user's spaces and search each one
            val spacesResp = client.newCall(
                Request.Builder()
                    .url("https://chat.googleapis.com/v1/spaces?pageSize=100")
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            ).execute()

            if (!spacesResp.isSuccessful) {
                spacesResp.body?.string() // consume to free connection
                Log.w(TAG, "spaces.list error ${spacesResp.code}")
                return@withContext null
            }

            val spacesBody = spacesResp.body?.string() ?: "{}"
            Log.d(TAG, "spaces.list: HTTP 200")
            val spacesJson = org.json.JSONObject(spacesBody)
            val spaces = spacesJson.optJSONArray("spaces")
            if (spaces == null) {
                Log.w(TAG, "No 'spaces' array in the response")
                return@withContext null
            }

            Log.d(TAG, "Searching prUrl=$prUrl in ${spaces.length()} spaces")

            for (i in 0 until spaces.length()) {
                val sName = spaces.getJSONObject(i).optString("name", "")
                if (sName.isEmpty() || sName == hintSpaceName) continue  // already tried
                val result = tryReactionInSpace(token, sName, prUrl, workingEmoji)
                if (result != null) {
                    Log.d(TAG, "👀 Found and reacted in space $sName")
                    return@withContext result
                }
            }

            Log.w(TAG, "No message with prUrl=$prUrl found in any space (${spaces.length()} checked)")
            null
        }
    }

    /** Looks for [prUrl] in the last 25 messages of [spaceName] and reacts with [workingEmoji] if found. */
    private suspend fun tryReactionInSpace(token: String, spaceName: String, prUrl: String, workingEmoji: String = "👀"): String? {
        return try {
            val listResp = client.newCall(
                Request.Builder()
                    .url("https://chat.googleapis.com/v1/$spaceName/messages?pageSize=25&orderBy=createTime%20desc")
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            ).execute()

            if (!listResp.isSuccessful) {
                listResp.body?.string() // consume to free connection
                Log.w(TAG, "messages.list($spaceName) error ${listResp.code}")
                return null
            }

            val respBody = listResp.body?.string() ?: "{}"
            val messages = org.json.JSONObject(respBody).optJSONArray("messages")
            if (messages == null) {
                Log.d(TAG, "messages.list($spaceName) has no messages")
                return null
            }

            var targetName: String? = null
            for (i in 0 until messages.length()) {
                val msg = messages.getJSONObject(i)
                // NOTE: Android's org.json escapes slashes "/" as "\/" when serializing
                // with toString(). We normalize before looking for the URL.
                val msgJson = msg.toString().replace("\\/", "/")
                if (msgJson.contains(prUrl)) {
                    targetName = msg.optString("name", "").takeIf { it.isNotEmpty() }
                    Log.d(TAG, "  → PR URL found in $spaceName msg[$i]: $targetName")
                    break
                }
            }

            if (targetName == null) {
                Log.d(TAG, "  prUrl is not in the ${messages.length()} messages of $spaceName")
                return null
            }

            val body = JSONObject().apply {
                put("emoji", JSONObject().apply { put("unicode", workingEmoji) })
            }.toString()

            val resp = client.newCall(
                Request.Builder()
                    .url("https://chat.googleapis.com/v1/$targetName/reactions")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()

            // The message is ALREADY resolved ($targetName): we do NOT return null because the
            // reaction failed. Losing the target here was the root cause of the verdict swap
            // and the approve emoji not landing when re-reviewing the same PR (the working
            // emoji already existed → 409). See resolveReactionHandle.
            val code = resp.code
            val bodyStr = resp.body?.string() ?: ""
            val newReactionName =
                if (resp.isSuccessful) org.json.JSONObject(bodyStr).optString("name").takeIf { it.isNotEmpty() }
                else null
            val existingReactionName =
                if (code == 409) findExistingUnicodeReaction(token, targetName, workingEmoji) else null
            when {
                resp.isSuccessful -> {}
                code == 409       -> Log.d(TAG, "👀 already existed on $targetName — reusing the handle")
                else              -> Log.w(TAG, "Reaction error $code on $targetName — message resolved anyway")
            }
            resolveReactionHandle(targetName, code, newReactionName, existingReactionName)
        } catch (e: Exception) {
            Log.w(TAG, "tryReactionInSpace($spaceName) exception: ${e.message}")
            null
        }
    }

    /**
     * Decides which handle to return after attempting the reaction on the already resolved
     * message. Key point: once the message is found ([targetName] != null) the RESOLUTION
     * succeeded and we NEVER return null because the reaction failed — that lost the target
     * and kept the verdict swap and the approve emoji from landing when re-reviewing a PR.
     *  - 2xx → name of the new reaction (lets us swap it later)
     *  - 409 → it already existed; reuses the existing reaction if we found it, else the messageName
     *  - other error → messageName (targeting still works; the later remove no-ops safely)
     */
    internal fun resolveReactionHandle(
        targetName: String?, httpCode: Int, newReactionName: String?, existingReactionName: String?
    ): String? = when {
        targetName == null   -> null
        httpCode in 200..299 -> newReactionName ?: targetName
        httpCode == 409      -> existingReactionName ?: targetName
        else                 -> targetName
    }

    /**
     * Best-effort: resource name of an [emoji] (unicode) reaction already present on
     * [messageName]. Used when creating the working emoji returned 409 (it already
     * existed) and we need its name to swap it for the verdict / approve emoji.
     */
    private suspend fun findExistingUnicodeReaction(token: String, messageName: String, emoji: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val resp = client.newCall(
                    Request.Builder()
                        .url("https://chat.googleapis.com/v1/$messageName/reactions")
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                ).execute()
                if (!resp.isSuccessful) { resp.body?.string(); return@withContext null }
                val arr = org.json.JSONObject(resp.body?.string() ?: "{}")
                    .optJSONArray("reactions") ?: return@withContext null
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    if (r.optJSONObject("emoji")?.optString("unicode") == emoji) {
                        return@withContext r.optString("name").takeIf { it.isNotEmpty() }
                    }
                }
                null
            } catch (e: Exception) {
                Log.w(TAG, "findExistingUnicodeReaction($messageName) exception: ${e.message}")
                null
            }
        }

    // Kept for compatibility — delegates to the new version
    suspend fun addEyesReaction(context: Context, chatContext: ChatContext): String? =
        addEyesReactionForPR(context, "", chatContext.spaceName)

    /**
     * Puts a reaction on [messageName]. [hexCodepoint] is the emoji's hex codepoint
     * (e.g. "2705" for ✅) — it is converted to the real character internally.
     */
    suspend fun addUnicodeReaction(context: Context, messageName: String, hexCodepoint: String): String? =
        addUnicodeReactionRaw(context, messageName, emojiFromHex(hexCodepoint))

    /**
     * Puts a reaction on [messageName] with the emoji character [emoji] as is
     * (e.g. "👀", "💬", "✅"). The Chat API expects the real unicode character.
     */
    suspend fun addUnicodeReactionRaw(context: Context, messageName: String, emoji: String): String? {
        val token = getAccessToken(context) ?: return null
        return withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("emoji", JSONObject().apply { put("unicode", emoji) })
            }.toString()
            val resp = client.newCall(
                Request.Builder()
                    .url("https://chat.googleapis.com/v1/$messageName/reactions")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            if (!resp.isSuccessful) {
                resp.body?.string() // consume to free connection
                Log.w(TAG, "addUnicodeReaction error ${resp.code}")
                null
            } else {
                org.json.JSONObject(resp.body?.string() ?: "{}").optString("name").takeIf { it.isNotEmpty() }
            }
        }
    }

    /**
     * Reacts on [messageName] with a custom emoji referenced by its RESOURCE NAME
     * ("customEmojis/…") — [emojiResourceName]. NOT by uid: the API marks CustomEmoji.uid
     * as output-only and a reaction identified by uid returns 400 INVALID_ARGUMENT.
     * The valid identifier to react with is `customEmoji.name`.
     */
    suspend fun addCustomEmojiReactionToMessage(context: Context, messageName: String, emojiResourceName: String): String? {
        val token = getAccessToken(context) ?: return null
        val body = JSONObject().apply {
            put("emoji", JSONObject().apply {
                put("customEmoji", JSONObject().apply { put("name", emojiResourceName) })
            })
        }.toString()
        return withContext(Dispatchers.IO) {
            val resp = client.newCall(
                Request.Builder()
                    .url("https://chat.googleapis.com/v1/$messageName/reactions")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string() ?: ""
                Log.w(TAG, "Custom emoji reaction error ${resp.code} name=$emojiResourceName msg=$messageName body=$errBody")
                null
            } else {
                org.json.JSONObject(resp.body?.string() ?: "{}").optString("name").takeIf { it.isNotEmpty() }
            }
        }
    }

    // Delete a reaction by its resource name
    // DELETE /v1/{reaction.name}
    override suspend fun removeReaction(context: Context, reactionName: String) {
        val token = getAccessToken(context) ?: return

        withContext(Dispatchers.IO) {
            val response = client.newCall(
                Request.Builder()
                    .url("https://chat.googleapis.com/v1/$reactionName")
                    .addHeader("Authorization", "Bearer $token")
                    .delete()
                    .build()
            ).execute()

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.w(TAG, "Remove reaction error ${response.code}: $errBody")
            } else {
                Log.d(TAG, "Reaction removed: $reactionName")
            }
        }
    }

    /**
     * Resolves the RESOURCE NAME ("customEmojis/…") of the custom emoji [shortCode] — the
     * identifier the API accepts for reacting (the uid is output-only and yields 400).
     * Caches the hit for the session. Returns null if the scope is missing / the API fails /
     * it does not exist. Deliberately no hardcoded fallback: the resource name differs per
     * workspace and cannot be pinned; the lookup is the only reliable source.
     */
    suspend fun findCustomEmojiName(context: Context, shortCode: String): String? {
        val cacheKey = shortCode.trim(':').lowercase()
        customEmojiNameCache[cacheKey]?.let {
            Log.d(TAG, "Custom emoji ':$shortCode:' from cache: $it")
            return it
        }
        return searchCustomEmojiName(context, cacheKey)?.also { customEmojiNameCache[cacheKey] = it }
    }

    /**
     * Lists the workspace's customEmojis and returns the RESOURCE NAME ("customEmojis/…")
     * of the emoji whose name matches [want] (shortCode without ':'), or null if there is
     * no token/scope, the API fails, or it is not listed. The caller caches the hit;
     * failures stay uncached so they are retried. The `name` (not the uid) is returned
     * because that is what the API accepts when reacting.
     */
    private suspend fun searchCustomEmojiName(context: Context, want: String): String? {
        val token = getAccessToken(context, REQUIRED_SCOPES + CUSTOM_EMOJI_SCOPE)
            ?: getAccessToken(context, REQUIRED_SCOPES)
            ?: run {
                Log.w(TAG, "No token — cannot look up custom emojis")
                return null
            }

        return withContext(Dispatchers.IO) {
            var pageToken: String? = null
            var page = 0
            var totalSeen = 0

            do {
                val url = buildString {
                    append("https://chat.googleapis.com/v1/customEmojis?pageSize=200")
                    if (!pageToken.isNullOrEmpty()) append("&pageToken=").append(pageToken)
                }
                val response = client.newCall(
                    Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
                ).execute()

                if (!response.isSuccessful) {
                    response.body?.string() // consume to free connection
                    Log.w(TAG, "Custom emoji list error ${response.code}")
                    return@withContext null
                }

                val json = JSONObject(response.body?.string() ?: "{}")
                val emojis = json.optJSONArray("customEmojis")

                if (emojis != null) {
                    totalSeen += emojis.length()
                    for (i in 0 until emojis.length()) {
                        val emoji = emojis.getJSONObject(i)
                        val candidates = listOf(
                            emoji.optString("emojiName"),
                            emoji.optString("shortCode"),
                            emoji.optString("name").substringAfterLast('/')
                        ).map { it.trim(':') }
                        if (candidates.any { it.equals(want, ignoreCase = true) }) {
                            val ref = emoji.optString("name").takeIf { it.isNotEmpty() }
                            Log.d(TAG, "  → ':$want:' found on page $page, name=$ref (uid=${emoji.optString("uid")})")
                            return@withContext ref
                        }
                    }
                }

                pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
                page++
            } while (!pageToken.isNullOrEmpty() && page < 25)

            Log.w(TAG, "Custom emoji ':$want:' not found in $totalSeen emojis ($page pages)")
            null
        }
    }

    // Reply in the thread of the original message. Returns true if the post succeeded.
    // POST /v1/spaces/{space}/messages?messageReplyOption=REPLY_MESSAGE_FALLBACK_TO_NEW_THREAD
    suspend fun replyInThread(context: Context, chatContext: ChatContext, text: String): Boolean {
        val token = getAccessToken(context) ?: run {
            Log.w(TAG, "No Google token — skipping reply")
            return false
        }

        val threadName = chatContext.threadName ?: chatContext.messageName

        // If we know neither the thread nor the messageName (chime format without
        // THREAD_KEY), we don't send the `thread` field as null (the API rejects it); the
        // reply falls back to a new thread via REPLY_MESSAGE_FALLBACK_TO_NEW_THREAD.
        val body = JSONObject().apply {
            put("text", text)
            threadName?.let { tn ->
                put("thread", JSONObject().apply { put("name", tn) })
            }
        }.toString()

        val spaceName = chatContext.spaceName
        val url = "https://chat.googleapis.com/v1/$spaceName/messages" +
                  "?messageReplyOption=REPLY_MESSAGE_FALLBACK_TO_NEW_THREAD"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.body?.string() // consume to free connection
                Log.w(TAG, "Reply error ${response.code}")
                false
            } else {
                Log.d(TAG, "💬 Reply posted in thread $threadName")
                true
            }
        }
    }

    /** Extracts the space ("spaces/S") from a message name ("spaces/S/messages/M"). null if it doesn't match. */
    internal fun spaceOfMessage(messageName: String): String? =
        Regex("^(spaces/[^/]+)/messages/[^/]+$").find(messageName.trim())?.groupValues?.get(1)

    /**
     * Replies under the REAL message [messageName] (the one resolved by the working-reaction
     * search, "spaces/S/messages/M"). Resolves the real space and thread with a GET on the
     * message, so it works with the unified Gmail/Chat format (chime), where the
     * notification's space is NOT a valid REST API resource name (see addEyesReactionForPR:
     * that is why reactions are posted on the resolved name, not the notification's).
     * Returns true if it posted.
     */
    suspend fun replyToMessage(context: Context, messageName: String, text: String): Boolean {
        val space = spaceOfMessage(messageName) ?: run {
            Log.w(TAG, "replyToMessage: invalid messageName: $messageName")
            return false
        }
        val token = getAccessToken(context) ?: run {
            Log.w(TAG, "No Google token — skipping reply")
            return false
        }
        return withContext(Dispatchers.IO) {
            // 1. GET the real message to learn its thread (and confirm the real space).
            val msgResp = client.newCall(
                Request.Builder()
                    .url("https://chat.googleapis.com/v1/$messageName")
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            ).execute()
            val threadName = if (msgResp.isSuccessful) {
                org.json.JSONObject(msgResp.body?.string() ?: "{}")
                    .optJSONObject("thread")?.optString("name")?.takeIf { it.isNotEmpty() }
            } else {
                msgResp.body?.string() // consume to free connection
                Log.w(TAG, "replyToMessage: GET $messageName → ${msgResp.code} (continuing without a thread)")
                null
            }
            // 2. POST the reply in the real space; in the message's thread if we know it,
            //    falling back to a new thread (REPLY_MESSAGE_FALLBACK_TO_NEW_THREAD).
            val body = JSONObject().apply {
                put("text", text)
                threadName?.let { tn -> put("thread", JSONObject().apply { put("name", tn) }) }
            }.toString()
            val resp = client.newCall(
                Request.Builder()
                    .url("https://chat.googleapis.com/v1/$space/messages" +
                         "?messageReplyOption=REPLY_MESSAGE_FALLBACK_TO_NEW_THREAD")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: ""
                Log.w(TAG, "replyToMessage error ${resp.code} space=$space thread=$threadName body=$err")
                false
            } else {
                Log.d(TAG, "💬 Reply posted in $space (thread=$threadName)")
                true
            }
        }
    }

    /**
     * Replies preferring the REAL resolved message ([resolvedMessageName], "spaces/S/messages/M",
     * the one found by the working-reaction search). If there is no valid one, falls back to the
     * notification's [chatContext]. This is the single place that decides chime-safe vs
     * notification — use it at EVERY reply site.
     */
    suspend fun replyPreferringResolved(
        context: Context, resolvedMessageName: String?, chatContext: ChatContext, text: String
    ): Boolean =
        if (resolvedMessageName != null && spaceOfMessage(resolvedMessageName) != null)
            replyToMessage(context, resolvedMessageName, text)
        else
            replyInThread(context, chatContext, text)

    // ── MessagingProvider ────────────────────────────────────────────────────

    /** Google Chat resource names all hang off "spaces/…". */
    override fun owns(resourceName: String): Boolean = resourceName.startsWith("spaces/")

    override suspend fun isConnected(context: Context): Boolean = hasRequiredScopes(context)

    /**
     * The first ("working") reaction has to be a unicode emoji: it is posted in the same
     * pass that locates the message. A custom-emoji shortcode falls back to 👀.
     */
    override suspend fun reactToPrMessage(
        context: Context, prUrl: String, hintSpaceName: String?, emojiSpec: String
    ): String? = addEyesReactionForPR(context, prUrl, hintSpaceName, EmojiSpec.toUnicode(emojiSpec) ?: "👀")

    /**
     * Reacts on [messageName] according to [emojiSpec]:
     *  - literal unicode emoji → sent as is
     *  - shortcode → workspace custom emoji if one exists with that name,
     *    otherwise the standard emoji with that shortcode
     * Returns the reaction resource name, or null if it could not be resolved/created.
     */
    override suspend fun react(context: Context, messageName: String, emojiSpec: String): String? {
        val s = EmojiSpec.normalize(emojiSpec)
        if (s.isEmpty()) return null
        if (!EmojiSpec.isShortcode(s)) {
            return runCatching { addUnicodeReactionRaw(context, messageName, s) }.getOrNull()
        }
        val ref = runCatching { findCustomEmojiName(context, s) }.getOrNull()
        if (ref != null) {
            val name = runCatching { addCustomEmojiReactionToMessage(context, messageName, ref) }.getOrNull()
            if (name != null) return name
            invalidateCustomEmoji(s)
        }
        EmojiSpec.toUnicode(s)?.let { ch ->
            return runCatching { addUnicodeReactionRaw(context, messageName, ch) }.getOrNull()
        }
        Log.w(TAG, "Emoji '$s' is neither a workspace custom emoji nor a known shortcode")
        return null
    }

    override suspend fun reply(
        context: Context, resolvedMessageName: String?, chatContext: ChatContext, text: String
    ): Boolean = replyPreferringResolved(context, resolvedMessageName, chatContext, text)
}
