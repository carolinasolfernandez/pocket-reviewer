package dev.pocketreviewer.messaging

import android.content.Context
import dev.pocketreviewer.api.ChatContext
import dev.pocketreviewer.api.GoogleChatClient
import java.util.concurrent.ConcurrentHashMap

/**
 * A chat platform the app can act on: find the message that carries a PR link,
 * react to it, and reply in its thread.
 *
 * All providers share one naming scheme so the rest of the app (history, manual
 * flow, notification extras) can pass plain strings around without knowing which
 * platform a message belongs to:
 *
 *   space    "<root>/<id>"
 *   message  "<root>/<id>/messages/<messageId>"
 *   thread   "<root>/<id>/threads/<threadId>"
 *   reaction "<message>/reactions/<reactionId>"
 *
 * Google Chat uses its native resource names (root = "spaces"); Slack uses
 * root = "slack" (see [SlackNames]). [Messaging] routes by that root.
 */
interface MessagingProvider {

    /** True if [resourceName] (space, message, thread or reaction) belongs to this provider. */
    fun owns(resourceName: String): Boolean

    /** True if the user has connected an account that can react and reply. */
    suspend fun isConnected(context: Context): Boolean

    /**
     * Locates the message that contains [prUrl] (trying [hintSpaceName] first) and
     * reacts to it with [emojiSpec]. Returns the reaction name, or — when the message
     * was found but the reaction could not be created — the message name. Null means
     * the message was not found (or the provider is not connected).
     */
    suspend fun reactToPrMessage(context: Context, prUrl: String, hintSpaceName: String?, emojiSpec: String): String?

    /** Reacts on [messageName] with [emojiSpec]. Returns the reaction name, or null. */
    suspend fun react(context: Context, messageName: String, emojiSpec: String): String?

    suspend fun removeReaction(context: Context, reactionName: String)

    /**
     * Replies in the thread of the PR message. Prefers [resolvedMessageName] (the real
     * message found by [reactToPrMessage]); falls back to [chatContext] from the notification.
     */
    suspend fun reply(context: Context, resolvedMessageName: String?, chatContext: ChatContext, text: String): Boolean
}

/** Single entry point for chat actions; routes each call to the provider that owns the name. */
object Messaging {

    val providers: List<MessagingProvider> = listOf(SlackClient, GoogleChatClient)

    /** Provider for [resourceName]; names nobody claims (and null) go to Google Chat. */
    fun providerFor(resourceName: String?): MessagingProvider =
        resourceName?.let { name -> providers.firstOrNull { it.owns(name) } } ?: GoogleChatClient

    // Active reaction per message (messageName → reactionName), so it can be removed
    // before the next one is added. In-memory only; the manual flow also persists it.
    private val activeReaction = ConcurrentHashMap<String, String>()

    fun setActiveReaction(messageName: String, reactionName: String) {
        activeReaction[messageName] = reactionName
    }

    fun getAndClearActiveReaction(messageName: String): String? = activeReaction.remove(messageName)

    /** "<message>/reactions/<id>" → "<message>"; a bare message name is returned unchanged. */
    fun messageOf(reactionOrMessageName: String): String = reactionOrMessageName.substringBefore("/reactions/")

    suspend fun reactToPrMessage(context: Context, prUrl: String, hintSpaceName: String?, emojiSpec: String): String? =
        providerFor(hintSpaceName).reactToPrMessage(context, prUrl, hintSpaceName, emojiSpec)

    suspend fun react(context: Context, messageName: String, emojiSpec: String): String? =
        providerFor(messageName).react(context, messageName, emojiSpec)

    suspend fun removeReaction(context: Context, reactionName: String) =
        providerFor(reactionName).removeReaction(context, reactionName)

    suspend fun reply(context: Context, resolvedMessageName: String?, chatContext: ChatContext, text: String): Boolean =
        providerFor(resolvedMessageName ?: chatContext.spaceName)
            .reply(context, resolvedMessageName, chatContext, text)
}
