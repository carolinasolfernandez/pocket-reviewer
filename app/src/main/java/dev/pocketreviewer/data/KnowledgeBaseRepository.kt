package dev.pocketreviewer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pocketreviewer.api.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Where the reviewer's rules come from. */
enum class KbSource(val id: String) {
    /** The generic rules shipped with the app (assets/default_knowledge_base.md). */
    BUILTIN("builtin"),
    /** Markdown files downloaded from a GitHub repository chosen by the user. */
    GITHUB("github"),
    /** Text pasted by the user. */
    TEXT("text");

    companion object {
        fun from(id: String?): KbSource = entries.firstOrNull { it.id == id } ?: BUILTIN
    }
}

/** A GitHub location to download the knowledge base from. */
data class KbRepoConfig(
    /** "owner/repo" (a full github.com URL is accepted too). */
    val repo: String = "",
    /** Branch, tag or commit. Blank = the repository's default branch. */
    val branch: String = "",
    /** Folder (all `.md` files under it, recursively) or a single file. Blank = whole repo. */
    val path: String = "",
)

/** Pure helpers for the knowledge base (no Android) so they can be unit-tested. */
object KbParsing {

    private val REPO = Regex("""^(?:https?://github\.com/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?$""")

    /** "owner/repo" or a github.com URL → (owner, repo), or null if it is neither. */
    fun parseRepo(raw: String): Pair<String, String>? =
        REPO.find(raw.trim())?.let { it.groupValues[1] to it.groupValues[2] }

    fun normalizePath(raw: String): String = raw.trim().trim('/')

    /** True if the blob at [filePath] belongs to the knowledge base rooted at [kbPath]. */
    fun matchesPath(filePath: String, kbPath: String): Boolean {
        val root = normalizePath(kbPath)
        val isDoc = filePath.endsWith(".md", ignoreCase = true) || filePath.endsWith(".mdx", ignoreCase = true)
        return when {
            root.isEmpty() -> isDoc
            filePath == root -> true                       // a single file, any extension
            else -> isDoc && filePath.startsWith("$root/")
        }
    }

    /** Name shown for [filePath] inside the KB: relative to the folder when there is one. */
    fun displayName(filePath: String, kbPath: String): String {
        val root = normalizePath(kbPath)
        return if (root.isNotEmpty() && filePath.startsWith("$root/")) filePath.removePrefix("$root/") else filePath
    }

    private val HEADER = Regex("(?m)^# === (.+?) ===$")

    /** Concatenates [files] (name → content) into one markdown document with file headers. */
    fun join(files: List<Pair<String, String>>): String =
        files.joinToString("\n\n") { (name, content) -> "# === $name ===\n\n${content.trim()}" }

    /** Inverse of [join]. Text without headers comes back as a single entry named [fallbackName]. */
    fun split(text: String, fallbackName: String): List<Pair<String, String>> {
        val headers = HEADER.findAll(text).toList()
        if (headers.isEmpty()) return listOf(fallbackName to text.trim())
        return headers.mapIndexed { i, m ->
            val end = if (i + 1 < headers.size) headers[i + 1].range.first else text.length
            m.groupValues[1].trim() to text.substring(m.range.last + 1, end).trim()
        }
    }

    /** Final rules text sent to the model: the base knowledge base plus the user's extra instructions. */
    fun compose(base: String, extraInstructions: String?): String {
        val extra = extraInstructions?.trim().orEmpty()
        return if (extra.isEmpty()) base.trim()
        else base.trim() + "\n\n## ADDITIONAL INSTRUCTIONS FROM THE USER\n" +
            "These take precedence over the rules above when they conflict.\n\n" + extra
    }
}

/**
 * The knowledge base the reviewer uses to build its prompt. The user picks one of
 * three sources ([KbSource]) and can add extra instructions on top of any of them.
 * If the selected source has no content yet (nothing downloaded / nothing pasted),
 * the built-in rules are used, so a review never runs without rules.
 */
object KnowledgeBaseRepository {

    private const val ASSET_DEFAULT = "default_knowledge_base.md"
    private const val FILE_GITHUB = "knowledge_base.md"
    private const val FILE_TEXT = "knowledge_base_custom.md"

    private const val KEY_SOURCE = "kb_source"
    private const val KEY_REPO = "kb_repo"
    private const val KEY_BRANCH = "kb_branch"
    private const val KEY_PATH = "kb_path"
    private const val KEY_EXTRA = "kb_extra_instructions"
    private const val KEY_LAST_UPDATE = "kb_last_update"
    private const val KEY_FILE_COUNT = "kb_file_count"
    private const val KEY_CHARS = "kb_chars"

    /** Guards against pulling a whole documentation site into every prompt. */
    const val MAX_FILES = 60
    const val MAX_CHARS = 300_000

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class KbFile(val name: String, val content: String)
    data class KbInfo(val lastUpdateMs: Long, val fileCount: Int, val chars: Int)

    data class KbSettings(
        val source: KbSource = KbSource.BUILTIN,
        val repo: KbRepoConfig = KbRepoConfig(),
        val extraInstructions: String = "",
    )

    // ── Settings ─────────────────────────────────────────────────────────────

    suspend fun settings(context: Context): KbSettings {
        val p = context.dataStore.data.first()
        return KbSettings(
            source = KbSource.from(p[stringPreferencesKey(KEY_SOURCE)]),
            repo = KbRepoConfig(
                repo = p[stringPreferencesKey(KEY_REPO)].orEmpty(),
                branch = p[stringPreferencesKey(KEY_BRANCH)].orEmpty(),
                path = p[stringPreferencesKey(KEY_PATH)].orEmpty(),
            ),
            extraInstructions = p[stringPreferencesKey(KEY_EXTRA)].orEmpty(),
        )
    }

    suspend fun saveSettings(context: Context, s: KbSettings) {
        context.dataStore.edit { p ->
            p[stringPreferencesKey(KEY_SOURCE)] = s.source.id
            p[stringPreferencesKey(KEY_REPO)] = s.repo.repo.trim()
            p[stringPreferencesKey(KEY_BRANCH)] = s.repo.branch.trim()
            p[stringPreferencesKey(KEY_PATH)] = KbParsing.normalizePath(s.repo.path)
            p[stringPreferencesKey(KEY_EXTRA)] = s.extraInstructions.trim()
        }
    }

    // ── Content ──────────────────────────────────────────────────────────────

    fun builtIn(context: Context): String =
        context.assets.open(ASSET_DEFAULT).bufferedReader().use { it.readText() }

    private fun readFile(context: Context, name: String): String? =
        File(context.filesDir, name).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    /** Text pasted by the user ("" if none). */
    fun customText(context: Context): String = readFile(context, FILE_TEXT).orEmpty()

    fun saveCustomText(context: Context, text: String) {
        File(context.filesDir, FILE_TEXT).writeText(text.trim())
    }

    /** Content of [source], or null if that source has nothing yet. */
    fun contentOf(context: Context, source: KbSource): String? = when (source) {
        KbSource.BUILTIN -> builtIn(context)
        KbSource.GITHUB -> readFile(context, FILE_GITHUB)
        KbSource.TEXT -> readFile(context, FILE_TEXT)
    }

    /**
     * The rules text for the prompt: the selected source (built-in rules if it is
     * still empty) plus the user's extra instructions.
     */
    suspend fun effective(context: Context): String {
        val s = settings(context)
        val base = contentOf(context, s.source) ?: builtIn(context)
        return KbParsing.compose(base, s.extraInstructions)
    }

    /** [source]'s content split into files, for display. */
    fun files(context: Context, source: KbSource): List<KbFile> {
        val text = contentOf(context, source) ?: return emptyList()
        val fallback = when (source) {
            KbSource.BUILTIN -> ASSET_DEFAULT
            KbSource.GITHUB -> "knowledge base"
            KbSource.TEXT -> "custom text"
        }
        return KbParsing.split(text, fallback).map { (name, content) -> KbFile(name, content) }
    }

    /** Metadata of the last GitHub download, or null if there never was one. */
    suspend fun info(context: Context): KbInfo? {
        val prefs = context.dataStore.data.first()
        val ts = prefs[longPreferencesKey(KEY_LAST_UPDATE)] ?: return null
        return KbInfo(
            lastUpdateMs = ts,
            fileCount = prefs[intPreferencesKey(KEY_FILE_COUNT)] ?: 0,
            chars = prefs[intPreferencesKey(KEY_CHARS)] ?: 0
        )
    }

    // ── GitHub download ──────────────────────────────────────────────────────

    /**
     * Downloads the markdown files at [config] and stores them concatenated.
     * [githubToken] is only needed for private repositories; public ones work without it
     * (subject to GitHub's unauthenticated rate limit).
     */
    suspend fun download(context: Context, config: KbRepoConfig, githubToken: String?): KbInfo =
        withContext(Dispatchers.IO) {
            val (owner, repo) = KbParsing.parseRepo(config.repo)
                ?: throw Exception("Repository must look like owner/repo")
            val base = "https://api.github.com/repos/$owner/$repo"

            val branch = config.branch.trim().ifEmpty {
                JSONObject(ghGet(base, githubToken)).optString("default_branch").ifEmpty { "main" }
            }

            val treeJson = JSONObject(ghGet("$base/git/trees/$branch?recursive=1", githubToken))
            val tree = treeJson.optJSONArray("tree") ?: JSONArray()
            val paths = (0 until tree.length())
                .map { tree.getJSONObject(it) }
                .filter { it.optString("type") == "blob" }
                .map { it.optString("path") }
                .filter { KbParsing.matchesPath(it, config.path) }
                .sorted()

            if (paths.isEmpty()) {
                val where = KbParsing.normalizePath(config.path).ifEmpty { "(repository root)" }
                throw Exception("No markdown files found at $where in $owner/$repo@$branch")
            }
            if (paths.size > MAX_FILES) {
                throw Exception("Found ${paths.size} markdown files (limit $MAX_FILES). Set a folder to narrow it down.")
            }

            val files = paths.map { path ->
                val encoded = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                KbParsing.displayName(path, config.path) to
                    ghGet("$base/contents/$encoded?ref=$branch", githubToken, accept = "application/vnd.github.raw")
            }
            val content = KbParsing.join(files)
            if (content.length > MAX_CHARS) {
                throw Exception("Knowledge base is ${content.length} characters (limit $MAX_CHARS). Set a folder to narrow it down.")
            }
            File(context.filesDir, FILE_GITHUB).writeText(content)

            val now = System.currentTimeMillis()
            context.dataStore.edit { prefs ->
                prefs[longPreferencesKey(KEY_LAST_UPDATE)] = now
                prefs[intPreferencesKey(KEY_FILE_COUNT)] = paths.size
                prefs[intPreferencesKey(KEY_CHARS)] = content.length
            }
            KbInfo(now, paths.size, content.length)
        }

    private fun ghGet(url: String, token: String?, accept: String = "application/vnd.github+json"): String {
        val builder = Request.Builder().url(url).addHeader("Accept", accept)
        if (!token.isNullOrBlank()) builder.addHeader("Authorization", "Bearer $token")
        val resp = client.newCall(builder.build()).execute()
        val body = resp.body?.string() ?: ""
        if (!resp.isSuccessful) {
            val hint = when (resp.code) {
                404 -> " (not found — check the repo, branch and path; private repos need a GitHub token with read access)"
                401, 403 -> " (the GitHub token is missing, invalid, or lacks access)"
                else -> ""
            }
            throw Exception("GitHub ${resp.code}$hint: ${body.take(200)}")
        }
        return body
    }
}
