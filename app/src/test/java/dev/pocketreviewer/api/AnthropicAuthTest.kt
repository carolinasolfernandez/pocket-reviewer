package dev.pocketreviewer.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

class AnthropicAuthTest {

    @Test
    fun generatePkce_challengeIsSha256OfVerifier() {
        val p = AnthropicAuth.generatePkce()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(p.verifier.toByteArray(Charsets.US_ASCII))
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        assertEquals(expected, p.challenge)
    }

    @Test
    fun generatePkce_verifierIsUrlSafeAndUnique() {
        val a = AnthropicAuth.generatePkce()
        val b = AnthropicAuth.generatePkce()
        assertTrue(a.verifier.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertTrue(a.verifier != b.verifier)
    }

    @Test
    fun generateState_isNonBlankAndUnique() {
        assertTrue(AnthropicAuth.generateState().isNotBlank())
        assertTrue(AnthropicAuth.generateState() != AnthropicAuth.generateState())
    }

    /**
     * Claude's authorize endpoint rejects short `state` with "Invalid request
     * format" on consent submit. It must be 32 bytes (43 url-safe base64 chars,
     * unpadded), matching `claude setup-token`. A 16-byte state (22 chars) fails.
     */
    @Test
    fun generateState_is32BytesOfEntropy() {
        val state = AnthropicAuth.generateState()
        assertEquals(43, state.length)
        assertTrue(state.matches(Regex("^[A-Za-z0-9_-]+$")))
        val decoded = Base64.getUrlDecoder().decode(state)
        assertEquals(32, decoded.size)
    }

    @Test
    fun isOAuthToken_detectsOatPrefix() {
        assertTrue(AnthropicAuth.isOAuthToken("sk-ant-oat01-abc"))
        assertFalse(AnthropicAuth.isOAuthToken("sk-ant-api03-abc"))
        assertFalse(AnthropicAuth.isOAuthToken(""))
    }

    @Test
    fun parseAuthCode_splitsCodeAndState() {
        assertEquals("abc" to "xyz", AnthropicAuth.parseAuthCode("abc#xyz"))
        assertEquals("abc" to "", AnthropicAuth.parseAuthCode("abc"))
        assertEquals("abc" to "xyz", AnthropicAuth.parseAuthCode("  abc#xyz  "))
        assertNull(AnthropicAuth.parseAuthCode("   "))
    }

    @Test
    fun buildAuthorizeUrl_containsRequiredParams() {
        val url = AnthropicAuth.buildAuthorizeUrl("CHAL", "STATE")
        assertTrue(url.startsWith(AnthropicAuth.AUTHORIZE_URL + "?"))
        assertTrue(url.contains("code_challenge=CHAL"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=STATE"))
        assertTrue(url.contains("client_id=" + AnthropicAuth.CLIENT_ID))
        assertTrue(url.contains("response_type=code"))
    }

    /**
     * Pins the OAuth endpoint constants to the values the live `claude setup-token`
     * flow uses (captured from Claude Code's own OAuth client). These drifted when
     * Anthropic migrated claude.ai/console.anthropic.com -> claude.com/platform.claude.com,
     * which made the authorize endpoint reject the request with "Invalid request format".
     */
    @Test
    fun oauthConstants_matchLiveSetupTokenFlow() {
        assertEquals("9d1c250a-e61b-44d9-88ed-5944d1962f5e", AnthropicAuth.CLIENT_ID)
        assertEquals("https://claude.com/cai/oauth/authorize", AnthropicAuth.AUTHORIZE_URL)
        assertEquals("https://platform.claude.com/oauth/code/callback", AnthropicAuth.REDIRECT_URI)
        assertEquals("https://platform.claude.com/v1/oauth/token", AnthropicAuth.TOKEN_URL)
        // Subscription (claude.ai) login is inference-only; the console scopes
        // org:create_api_key / user:profile make the authorize request invalid.
        assertEquals("user:inference", AnthropicAuth.SCOPES)
    }

    @Test
    fun buildAuthorizeUrl_usesManualRedirectAndInferenceScope() {
        val url = AnthropicAuth.buildAuthorizeUrl("CHAL", "STATE")
        assertTrue(url.contains("code=true"))
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback"))
        assertTrue(url.contains("scope=user%3Ainference"))
    }

    private val NOW = 1_000_000L

    @Test
    fun resolve_freshOauth_noRefresh() {
        val s = AnthropicAuth.resolveAuthScheme("acc", "ref", NOW + 5 * 60_000, null, NOW)
        assertTrue(s is AnthropicAuth.AuthScheme.OAuth)
        s as AnthropicAuth.AuthScheme.OAuth
        assertEquals("acc", s.accessToken)
        assertFalse(s.needsRefresh)
    }

    @Test
    fun resolve_expiredOauth_needsRefresh() {
        val s = AnthropicAuth.resolveAuthScheme("acc", "ref", NOW - 1, null, NOW)
        s as AnthropicAuth.AuthScheme.OAuth
        assertTrue(s.needsRefresh)
    }

    @Test
    fun resolve_oauthWithinSkew_needsRefresh() {
        // expiry is 30s ahead but skew is 60s -> needs refresh
        val s = AnthropicAuth.resolveAuthScheme("acc", "ref", NOW + 30_000, null, NOW)
        s as AnthropicAuth.AuthScheme.OAuth
        assertTrue(s.needsRefresh)
    }

    @Test
    fun resolve_oauthNullExpiry_needsRefresh() {
        val s = AnthropicAuth.resolveAuthScheme("acc", "ref", null, null, NOW)
        s as AnthropicAuth.AuthScheme.OAuth
        assertTrue(s.needsRefresh)
    }

    @Test
    fun resolve_pastedOatToken_isOauthNoRefresh() {
        val s = AnthropicAuth.resolveAuthScheme(null, null, null, "sk-ant-oat01-abc", NOW)
        s as AnthropicAuth.AuthScheme.OAuth
        assertEquals("sk-ant-oat01-abc", s.accessToken)
        assertNull(s.refreshToken)
        assertFalse(s.needsRefresh)
    }

    @Test
    fun resolve_classicApiKey() {
        val s = AnthropicAuth.resolveAuthScheme(null, null, null, "sk-ant-api03-abc", NOW)
        assertEquals(AnthropicAuth.AuthScheme.ApiKey("sk-ant-api03-abc"), s)
    }

    @Test
    fun resolve_nothing_isNone() {
        assertEquals(AnthropicAuth.AuthScheme.None,
            AnthropicAuth.resolveAuthScheme(null, null, null, null, NOW))
        assertEquals(AnthropicAuth.AuthScheme.None,
            AnthropicAuth.resolveAuthScheme(null, null, null, "  ", NOW))
    }

    /**
     * Subscription OAuth tokens are gated behind the Claude Code identity: without
     * this exact first system block Anthropic returns a (misleading) 429. Verified
     * live — same token: no identity -> 429, with identity -> 200.
     */
    @Test
    fun claudeCodeSystemPrompt_isExactRequiredString() {
        assertEquals(
            "You are Claude Code, Anthropic's official CLI for Claude.",
            AnthropicAuth.CLAUDE_CODE_SYSTEM_PROMPT
        )
    }

    @Test
    fun buildSystemBlocks_prependsClaudeCodeIdentityOnlyForOAuth() {
        val oauth = AnthropicAuth.buildSystemBlocks(isOAuth = true, reviewSystemPrompt = "REVIEW")
        assertEquals(2, oauth.size)
        assertEquals(AnthropicAuth.CLAUDE_CODE_SYSTEM_PROMPT,
            oauth[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("REVIEW", oauth[1].jsonObject["text"]!!.jsonPrimitive.content)
        // cache breakpoint stays on the review block, not the identity block
        assertNull(oauth[0].jsonObject["cache_control"])
        assertEquals("ephemeral",
            oauth[1].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val apiKey = AnthropicAuth.buildSystemBlocks(isOAuth = false, reviewSystemPrompt = "REVIEW")
        assertEquals(1, apiKey.size)
        assertEquals("REVIEW", apiKey[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun applyAuthHeaders_oauth() {
        val req = AnthropicAuth.applyAuthHeaders(
            Request.Builder().url("https://api.anthropic.com/v1/messages"),
            "tok", isOAuth = true
        ).build()
        assertEquals("Bearer tok", req.header("Authorization"))
        assertEquals("oauth-2025-04-20", req.header("anthropic-beta"))
        assertNull(req.header("x-api-key"))
    }

    @Test
    fun applyAuthHeaders_apiKey() {
        val req = AnthropicAuth.applyAuthHeaders(
            Request.Builder().url("https://api.anthropic.com/v1/messages"),
            "key", isOAuth = false
        ).build()
        assertEquals("key", req.header("x-api-key"))
        assertNull(req.header("Authorization"))
        assertNull(req.header("anthropic-beta"))
    }
}
