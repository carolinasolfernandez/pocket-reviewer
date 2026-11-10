package dev.pocketreviewer.util

/**
 * Where GitHub lives: github.com, or a GitHub Enterprise Server host chosen by the
 * user. Pure (no Android) → unit-testable.
 */
object GitHubHost {

    const val DEFAULT = "github.com"

    /**
     * Cleans what the user typed into a bare host: no scheme, path, or trailing slash,
     * lowercase. Blank or unusable input falls back to [DEFAULT].
     */
    fun normalize(raw: String?): String {
        val host = raw.orEmpty().trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/')
            .trim()
        return if (host.isEmpty() || !host.matches(Regex("^[a-z0-9.-]+(:\\d+)?$"))) DEFAULT else host
    }

    fun isEnterprise(host: String): Boolean = normalize(host) != DEFAULT

    /** REST API root: api.github.com, or `<host>/api/v3` on GitHub Enterprise Server. */
    fun apiBase(host: String): String {
        val h = normalize(host)
        return if (h == DEFAULT) "https://api.github.com" else "https://$h/api/v3"
    }

    /** Web root, e.g. for building or recognizing PR links. */
    fun webBase(host: String): String = "https://${normalize(host)}"
}
