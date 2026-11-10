package dev.pocketreviewer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ReviewEntry(
    val prKey: String,
    val prUrl: String,
    val verdict: String,        // "APPROVE" | "REQUEST_CHANGES" | "COMMENT"
    val reviewText: String,
    val timestampMs: Long,
    val wasPosted: Boolean = false,
    val wasApproved: Boolean = false,
    val wasMerged: Boolean = false,
    val errorMessage: String? = null,
    // PR status: "PENDING" | "REVIEWED" | "IGNORED"
    val status: String = "REVIEWED",
    // CI checks failing at review time (empty = all OK or could not be verified)
    val ciFailingChecks: List<String> = emptyList(),
    // Overall CI state at review time: NONE | PENDING | SUCCESS | FAILURE | UNKNOWN
    val ciState: String = "UNKNOWN",
    // Merge conflicts at review time (mergeable=false / "dirty")
    val hasConflicts: Boolean = false,
    val mergeableState: String = "unknown",
    // Chat context for reactions (PENDING only)
    val chatSpaceName: String? = null,
    val chatMessageName: String? = null,
    val chatThreadName: String? = null,
    val msgId: String? = null,
    val isMultiPR: Boolean = false,
    // ── Manual flow state (history-driven) ─────────────────────────────────
    // The real chat message resolved when the working emoji is placed; every reaction targets it.
    val resolvedMessageName: String? = null,
    // The app's current reaction on the message (removed before adding the next).
    val activeReactionName: String? = null,
    // Which manual step is awaiting the user: REVIEW|APPROVE|MERGE|POST_GITHUB|CHAT_REPLY|DONE.
    val manualStage: String? = null,
    // True while a step service is running (dialog shows a spinner, disables the control).
    val manualRunning: Boolean = false
)

object ReviewHistoryRepository {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private const val FILE_NAME = "review_history.json"
    private const val MAX_ENTRIES = 50

    // Emitted every time the history changes (new PR, status change, etc.).
    // The UI collects it to auto-refresh the history without any user action.
    // extraBufferCapacity=1 allows tryEmit() without an active collector (does not suspend).
    private val mutex = Mutex()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> get() = _changes
    private fun notifyChange() { _changes.tryEmit(Unit) }

    private fun file(context: Context) =
        File(context.filesDir, FILE_NAME)

    private fun writeAtomic(context: Context, list: List<ReviewEntry>) {
        val target = file(context)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(list))
        if (!tmp.renameTo(target)) {
            // The atomic rename failed (rare): write directly so the update isn't lost.
            android.util.Log.w("ReviewHistory", "atomic rename failed; writing target directly")
            target.writeText(json.encodeToString(list))
            tmp.delete()
        }
    }

    fun load(context: Context): List<ReviewEntry> {
        val f = file(context)
        return try {
            val text = f.readText()
            json.decodeFromString<List<ReviewEntry>>(text)
        } catch (_: Exception) {
            if (f.exists() && f.length() > 0) {
                val corrupt = File(context.filesDir, "$FILE_NAME.corrupt")
                f.renameTo(corrupt)
            }
            emptyList()
        }
    }

    suspend fun add(context: Context, entry: ReviewEntry) {
        mutex.withLock {
            val current = load(context).toMutableList()
            // Avoid duplicates of the same PR in the same session
            current.removeAll { it.prKey == entry.prKey && it.timestampMs == entry.timestampMs }
            current.add(0, entry)
            // Keep only the last MAX_ENTRIES
            val trimmed = current.take(MAX_ENTRIES)
            writeAtomic(context, trimmed)
        }
        notifyChange()
    }

    /**
     * Inserts a PENDING entry for [entry].prKey only if there isn't already a live
     * (PENDING) one — idempotent so re-detections of the same PR don't pile up.
     * Returns the timestampMs of the existing-or-new PENDING entry.
     */
    suspend fun ensurePending(context: Context, entry: ReviewEntry): Long {
        val ts = mutex.withLock {
            val current = load(context).toMutableList()
            val existing = current.firstOrNull { it.prKey == entry.prKey && it.status == "PENDING" }
            if (existing != null) return@withLock existing.timestampMs
            current.add(0, entry)
            writeAtomic(context, current.take(MAX_ENTRIES))
            entry.timestampMs
        }
        notifyChange()
        return ts
    }

    /**
     * Records a finished review. If a PENDING entry already exists for this PR (created
     * on detection), it is updated in place — preserving its timestamp + chat context —
     * and flipped to REVIEWED. Otherwise a fresh REVIEWED entry is prepended.
     */
    suspend fun upsertReviewed(context: Context, entry: ReviewEntry) {
        mutex.withLock {
            val current = load(context).toMutableList()
            val idx = current.indexOfFirst { it.prKey == entry.prKey && it.status == "PENDING" }
            if (idx >= 0) {
                val old = current[idx]
                current[idx] = entry.copy(
                    timestampMs     = old.timestampMs,
                    status          = "REVIEWED",
                    chatSpaceName   = entry.chatSpaceName   ?: old.chatSpaceName,
                    chatMessageName = entry.chatMessageName ?: old.chatMessageName,
                    chatThreadName  = entry.chatThreadName  ?: old.chatThreadName,
                    isMultiPR       = entry.isMultiPR || old.isMultiPR
                )
            } else {
                current.removeAll { it.prKey == entry.prKey && it.timestampMs == entry.timestampMs }
                current.add(0, entry)
            }
            writeAtomic(context, current.take(MAX_ENTRIES))
        }
        notifyChange()
    }

    suspend fun updateEntry(context: Context, prKey: String, timestampMs: Long, update: (ReviewEntry) -> ReviewEntry) {
        mutex.withLock {
            val current = load(context).toMutableList()
            val idx = current.indexOfFirst { it.prKey == prKey && it.timestampMs == timestampMs }
            if (idx >= 0) {
                current[idx] = update(current[idx])
                writeAtomic(context, current)
                notifyChange()
            }
        }
    }

    /**
     * Updates the MOST RECENT entry for [prKey] (the list is ordered newest-first by
     * insertion). Useful when the exact timestamp is unknown, e.g. an action
     * triggered from a notification.
     */
    suspend fun updateLatestByKey(context: Context, prKey: String, update: (ReviewEntry) -> ReviewEntry) {
        mutex.withLock {
            val current = load(context).toMutableList()
            val idx = current.indexOfFirst { it.prKey == prKey }
            if (idx >= 0) {
                current[idx] = update(current[idx])
                writeAtomic(context, current)
                notifyChange()
            }
        }
    }

    fun clear(context: Context) {
        file(context).delete()
        notifyChange()
    }
}

// Serialization extensions for List<ReviewEntry>
private fun Json.encodeToString(list: List<ReviewEntry>): String =
    encodeToString(kotlinx.serialization.builtins.ListSerializer(ReviewEntry.serializer()), list)

private fun Json.decodeFromString(text: String): List<ReviewEntry> =
    decodeFromString(kotlinx.serialization.builtins.ListSerializer(ReviewEntry.serializer()), text)
