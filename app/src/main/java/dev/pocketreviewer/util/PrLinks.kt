package dev.pocketreviewer.util

/**
 * Finds GitHub pull request links in free text and applies the user's filters.
 * Pure (no Android) so it can be unit-tested on the JVM.
 *
 * Every function takes the GitHub host to look for: github.com by default, or the
 * user's GitHub Enterprise Server host. Links to any other host are ignored, because
 * the app holds a token for one host only.
 */
object PrLinks {

    private fun regexFor(host: String): Regex {
        val h = Regex.escape(GitHubHost.normalize(host))
        return Regex("""https://$h/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/pull/(\d+)""", RegexOption.IGNORE_CASE)
    }

    /** Every distinct PR linked in [text], in order of appearance, unfiltered. */
    fun findAll(text: String, host: String = GitHubHost.DEFAULT): List<PullRequest> =
        regexFor(host).findAll(text).map { m ->
            val (owner, repo, number) = m.destructured
            PullRequest(url = m.value, owner = owner, repo = repo, number = number.toInt(), key = "$owner/$repo#$number")
        }.distinctBy { it.key.lowercase() }.toList()

    /** Parses a single PR URL (surrounding text is ignored), or null. */
    fun parse(url: String, host: String = GitHubHost.DEFAULT): PullRequest? = findAll(url.trim(), host).firstOrNull()

    /**
     * True if [pr] passes the filters:
     *  - [allowedOwners] empty → any owner; otherwise the owner must be listed (case-insensitive).
     *  - [ignoredRepos] entries are either "repo" (any owner) or "owner/repo".
     */
    fun isAllowed(pr: PullRequest, allowedOwners: List<String>, ignoredRepos: List<String>): Boolean {
        if (allowedOwners.isNotEmpty() && allowedOwners.none { it.trim().equals(pr.owner, ignoreCase = true) }) {
            return false
        }
        val full = "${pr.owner}/${pr.repo}"
        return ignoredRepos.none { raw ->
            val entry = raw.trim()
            if (entry.contains('/')) entry.equals(full, ignoreCase = true)
            else entry.equals(pr.repo, ignoreCase = true)
        }
    }

    /** PRs in [text] that pass the filters. */
    fun find(
        text: String, allowedOwners: List<String>, ignoredRepos: List<String>,
        host: String = GitHubHost.DEFAULT
    ): List<PullRequest> = findAll(text, host).filter { isAllowed(it, allowedOwners, ignoredRepos) }
}
