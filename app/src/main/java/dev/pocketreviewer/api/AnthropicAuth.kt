package dev.pocketreviewer.api

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pocketreviewer.config.SecretStore
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Single source of truth for Anthropic authentication. Supports two credential
 * shapes, auto-detected at request time:
 *  - OAuth (from `claude setup-token` / in-app login): Authorization: Bearer + oauth beta header
 *  - Classic API key: x-api-key
 *
 * OAuth constants below reuse Claude Code's public OAuth client. They are
 * best-known values and MUST be verified against a live `claude setup-token`
 * run before relying on the flow.
 */
object AnthropicAuth {

    // ── OAuth constants ──────────────────────────────────────────────────────
    // Verified against a live `claude setup-token` run (Claude Code 2.1.169):
    // the subscription login uses claude.com/cai for authorize, platform.claude.com
    // for the manual-paste redirect + token exchange, and the inference-only scope.
    // (Older claude.ai / console.anthropic.com values now return "Invalid request
    // format" after Anthropic's domain migration.)
    const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    const val AUTHORIZE_URL = "https://claude.com/cai/oauth/authorize"
    const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
    const val REDIRECT_URI = "https://platform.claude.com/oauth/code/callback"
    const val SCOPES = "user:inference"

    const val OAUTH_BETA_HEADER = "oauth-2025-04-20"

    // Subscription OAuth tokens are gated behind the Claude Code identity: a request
    // whose first `system` block isn't this exact string is rejected by Anthropic with
    // a (misleading) HTTP 429 rate_limit_error. API-key requests don't need it.
    const val CLAUDE_CODE_SYSTEM_PROMPT =
        "You are Claude Code, Anthropic's official CLI for Claude."

    data class PkceCodes(val verifier: String, val challenge: String)

    private fun urlB64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun generatePkce(): PkceCodes {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = urlB64(bytes)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return PkceCodes(verifier, urlB64(digest))
    }

    // Claude's authorize endpoint rejects short `state` values with "Invalid
    // request format" on consent submit. Match `claude setup-token` (32 bytes).
    fun generateState(): String =
        urlB64(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun isOAuthToken(value: String): Boolean = value.startsWith("sk-ant-oat")

    /** Parses the pasted authorization code, which arrives as `code#state` or `code`. */
    fun parseAuthCode(raw: String): Pair<String, String>? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val i = t.indexOf('#')
        return if (i >= 0) t.substring(0, i) to t.substring(i + 1) else t to ""
    }

    fun buildAuthorizeUrl(challenge: String, state: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val params = listOf(
            "code=true",
            "client_id=$CLIENT_ID",
            "response_type=code",
            "redirect_uri=${enc(REDIRECT_URI)}",
            "scope=${enc(SCOPES)}",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
            "state=$state"
        ).joinToString("&")
        return "$AUTHORIZE_URL?$params"
    }

    private const val REFRESH_SKEW_MS = 60_000L

    sealed interface AuthScheme {
        object None : AuthScheme
        data class ApiKey(val key: String) : AuthScheme
        data class OAuth(
            val accessToken: String,
            val refreshToken: String?,
            val needsRefresh: Boolean
        ) : AuthScheme
    }

    fun resolveAuthScheme(
        oauthAccess: String?,
        oauthRefresh: String?,
        oauthExpiryMs: Long?,
        apiKey: String?,
        nowMs: Long
    ): AuthScheme {
        if (!oauthAccess.isNullOrBlank()) {
            val needsRefresh = oauthExpiryMs == null || nowMs >= oauthExpiryMs - REFRESH_SKEW_MS
            return AuthScheme.OAuth(oauthAccess, oauthRefresh?.takeIf { it.isNotBlank() }, needsRefresh)
        }
        val key = apiKey?.trim().orEmpty()
        if (key.isBlank()) return AuthScheme.None
        return if (isOAuthToken(key)) AuthScheme.OAuth(key, null, needsRefresh = false)
        else AuthScheme.ApiKey(key)
    }

    fun applyAuthHeaders(
        builder: Request.Builder,
        accessToken: String,
        isOAuth: Boolean
    ): Request.Builder {
        if (isOAuth) {
            builder.header("Authorization", "Bearer $accessToken")
            builder.header("anthropic-beta", OAUTH_BETA_HEADER)
        } else {
            builder.header("x-api-key", accessToken)
        }
        return builder
    }

    // DataStore keys (reuses the same store as ReviewManager: context.dataStore)
    private val KEY_API     = stringPreferencesKey("anthropic_key")
    private val KEY_ACCESS  = stringPreferencesKey("anthropic_oauth_access")
    private val KEY_REFRESH = stringPreferencesKey("anthropic_oauth_refresh")
    private val KEY_EXPIRY  = stringPreferencesKey("anthropic_oauth_expiry")

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSec: Long
    )

    private fun parseTokenResponse(body: String): TokenResponse {
        val o = json.parseToJsonElement(body).jsonObject
        val access = o["access_token"]?.jsonPrimitive?.contentOrNull
            ?: {
                try {
                    Log.d("AnthropicAuth", "OAuth response missing access_token (body redacted)")
                } catch (e: Exception) {
                    // Silently ignore logging failures
                }
                throw Exception("OAuth token endpoint returned invalid response")
            }.invoke()
        val refresh = o["refresh_token"]?.jsonPrimitive?.contentOrNull
        val expires = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        return TokenResponse(access, refresh, expires)
    }

    private suspend fun postToken(payload: JsonObject): TokenResponse =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(TOKEN_URL)
                .addHeader("content-type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                try {
                    Log.d("AnthropicAuth", "OAuth token endpoint returned ${resp.code} (body redacted)")
                } catch (e: Exception) {
                    // Silently ignore logging failures
                }
                throw Exception("OAuth token endpoint returned ${resp.code}")
            }
            parseTokenResponse(body)
        }

    suspend fun exchangeCode(code: String, state: String, verifier: String): TokenResponse =
        postToken(buildJsonObject {
            put("grant_type", "authorization_code")
            put("code", code)
            put("state", state)
            put("client_id", CLIENT_ID)
            put("redirect_uri", REDIRECT_URI)
            put("code_verifier", verifier)
        })

    suspend fun refreshTokens(refreshToken: String): TokenResponse =
        postToken(buildJsonObject {
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
            put("client_id", CLIENT_ID)
        })

    suspend fun storeOAuth(context: Context, tr: TokenResponse) {
        val expiryMs = System.currentTimeMillis() + tr.expiresInSec * 1000L
        context.dataStore.edit { p ->
            p[KEY_ACCESS] = SecretStore.encrypt(tr.accessToken)
            if (tr.refreshToken != null) p[KEY_REFRESH] = SecretStore.encrypt(tr.refreshToken)
            p[KEY_EXPIRY] = expiryMs.toString()
        }
    }

    suspend fun clearOAuth(context: Context) {
        context.dataStore.edit { p ->
            p.remove(KEY_ACCESS); p.remove(KEY_REFRESH); p.remove(KEY_EXPIRY)
        }
    }

    suspend fun isOAuthConnected(context: Context): Boolean {
        val prefs = context.dataStore.data.first()
        return !prefs[KEY_ACCESS].isNullOrBlank()
    }

    /** Whether the credential that will actually be sent is an OAuth bearer (login
     *  token OR a pasted `sk-ant-oat…` key), as opposed to a classic API key. */
    suspend fun activeSchemeIsOAuth(context: Context): Boolean {
        val prefs = context.dataStore.data.first()
        return resolveAuthScheme(
            oauthAccess = SecretStore.decrypt(prefs[KEY_ACCESS]),
            oauthRefresh = SecretStore.decrypt(prefs[KEY_REFRESH]),
            oauthExpiryMs = prefs[KEY_EXPIRY]?.toLongOrNull(),
            apiKey = SecretStore.decrypt(prefs[KEY_API]),
            nowMs = System.currentTimeMillis()
        ) is AuthScheme.OAuth
    }

    /**
     * Builds the Messages API `system` array. For OAuth (subscription) credentials
     * the Claude Code identity block MUST come first or Anthropic returns 429; the
     * review prompt stays the cached (`cache_control`) prefix in both cases.
     */
    fun buildSystemBlocks(isOAuth: Boolean, reviewSystemPrompt: String): JsonArray =
        buildJsonArray {
            if (isOAuth) add(buildJsonObject {
                put("type", "text")
                put("text", CLAUDE_CODE_SYSTEM_PROMPT)
            })
            add(buildJsonObject {
                put("type", "text")
                put("text", reviewSystemPrompt)
                put("cache_control", buildJsonObject {
                    put("type", "ephemeral")
                    put("ttl", "1h")
                })
            })
        }

    /** Resolves the active credential (refreshing OAuth if expired) and applies headers. */
    suspend fun applyTo(context: Context, builder: Request.Builder) {
        val prefs = context.dataStore.data.first()
        val scheme = resolveAuthScheme(
            oauthAccess = SecretStore.decrypt(prefs[KEY_ACCESS]),
            oauthRefresh = SecretStore.decrypt(prefs[KEY_REFRESH]),
            oauthExpiryMs = prefs[KEY_EXPIRY]?.toLongOrNull(),
            apiKey = SecretStore.decrypt(prefs[KEY_API]),
            nowMs = System.currentTimeMillis()
        )
        when (scheme) {
            is AuthScheme.None ->
                throw Exception("Claude is not configured: add an Anthropic API key or sign in with Claude (Settings → Accounts)")
            is AuthScheme.ApiKey ->
                applyAuthHeaders(builder, scheme.key, isOAuth = false)
            is AuthScheme.OAuth -> {
                val token = if (scheme.needsRefresh) {
                    val refresh = scheme.refreshToken
                    if (refresh == null) {
                        clearOAuth(context)
                        throw Exception("The Claude session expired. Sign in with Claude again.")
                    }
                    try {
                        val tr = refreshTokens(refresh)
                        storeOAuth(context, tr)
                        tr.accessToken
                    } catch (e: Exception) {
                        clearOAuth(context)
                        throw Exception(
                            "Could not renew the Claude session (it may have been revoked). " +
                                "Sign in with Claude again. Details: ${e.message}"
                        )
                    }
                } else {
                    scheme.accessToken
                }
                applyAuthHeaders(builder, token, isOAuth = true)
            }
        }
    }
}
