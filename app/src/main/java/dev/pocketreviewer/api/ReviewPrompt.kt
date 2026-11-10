package dev.pocketreviewer.api

import dev.pocketreviewer.config.AppConfig

/**
 * Builds the two halves of the review prompt. Pure (no Android) → unit-testable.
 *
 *  - [system]: instructions + knowledge base + severities + response format. It only
 *    depends on the user's configuration, so it is byte-identical across PRs and can
 *    be prompt-cached.
 *  - [user]: everything specific to one PR (repo rules, description, diff, comments).
 */
object ReviewPrompt {

    fun system(knowledgeBase: String, reviewLanguage: String): String = """
You are an automated code reviewer for GitHub pull requests. Review the pull request you are given against the knowledge base below and against any repo-specific rules included with the PR.

## KNOWLEDGE BASE
These are the rules and context to base the review on.

${knowledgeBase.trim()}

## SEVERITY LEVELS
- **BLOCKER:** security hole, data loss, or a crash/outage in production
- **ERROR:** a clear bug or a clear violation of a rule in the knowledge base
- **WARNING:** a likely problem or meaningful technical debt
- **INFO:** an optional suggestion or style improvement

## EXISTING FEEDBACK ON THIS PR
Before writing findings, read the EXISTING REVIEWS, INLINE REVIEW COMMENTS, and PR DISCUSSION COMMENTS sections provided with the PR (if present). Do not repeat points already raised or resolved; engage with open questions and acknowledge the author's replies. On a re-review, explicitly state which prior points were addressed and which remain open.

## RESPONSE FORMAT

The very first line must be exactly one of these words, in English and on its own: `APPROVE`, `REQUEST_CHANGES`, or `COMMENT`

Then a 1-2 sentence summary of the change and your overall assessment.

**Findings:**
For each issue: `[SEVERITY] file:line [RULE_CODE] — concise description`
Example: `[ERROR] src/users/service.py:42 [COR-2] — user can be None here; the next line dereferences it`
Use the rule code from the knowledge base when one applies; omit it otherwise.
If an obvious fix exists, include a code block with the replacement.

**Rules verified:** briefly list the rules this PR correctly follows (positive signal).

If this is a re-review: explicitly note which previous issues were fixed and which remain open.

If the PR is clean: say so and list the verified rules. Do not invent findings.

Write the review in ${reviewLanguage.trim().ifEmpty { "English" }}. Keep the verdict word, severity labels and rule codes exactly as specified above.
    """.trimIndent()

    private val LANGUAGES = listOf(
        listOf(".kt", ".kts") to "Kotlin",
        listOf(".java") to "Java",
        listOf(".py") to "Python",
        listOf(".ts", ".tsx") to "TypeScript",
        listOf(".js", ".jsx", ".mjs", ".cjs") to "JavaScript",
        listOf(".go") to "Go",
        listOf(".rs") to "Rust",
        listOf(".rb") to "Ruby",
        listOf(".php") to "PHP",
        listOf(".cs") to "C#",
        listOf(".swift") to "Swift",
        listOf(".c", ".h", ".cc", ".cpp", ".hpp") to "C/C++",
        listOf(".scala") to "Scala",
        listOf(".dart") to "Dart",
        listOf(".sql") to "SQL",
        listOf(".sh", ".bash") to "Shell",
        listOf(".tf") to "Terraform",
        listOf(".yml", ".yaml") to "YAML",
        listOf(".css", ".scss") to "CSS",
        listOf(".html") to "HTML",
        listOf(".md", ".mdx") to "Markdown",
    )

    /** Languages touched by [fileNames], most frequent first (at most [max]). */
    fun detectStack(fileNames: List<String>, max: Int = 5): List<String> {
        val counts = LinkedHashMap<String, Int>()
        for (name in fileNames) {
            val lower = name.lowercase()
            val lang = when {
                lower.endsWith("dockerfile") -> "Docker"
                else -> LANGUAGES.firstOrNull { (exts, _) -> exts.any { lower.endsWith(it) } }?.second
            } ?: continue
            counts[lang] = (counts[lang] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.value }.take(max).map { it.key }
    }

    fun user(
        title: String, author: String, headRef: String, baseRef: String,
        body: String, fileList: String, diffText: String, stack: List<String>,
        existingCommentsSection: String = "",
        relatedPRsSection: String = "",
        reReviewSection: String = "",
        repoRulesSection: String = "",
        maxDiffChars: Int = AppConfig.DEFAULT_MAX_DIFF_CHARS,
    ): String {
        val truncated = diffText.length > maxDiffChars
        val stackLine = stack.joinToString(", ").ifEmpty { "not detected" }
        return """
$repoRulesSection
$reReviewSection

## PR TO REVIEW

**Title:** $title
**Author:** $author
**Branch:** $headRef → $baseRef
**Languages touched by this PR:** $stackLine
**Description:** $body

**Modified files:**
$fileList

**Diff:**${if (truncated) " (truncated to the first $maxDiffChars characters — say so if it limits the review)" else ""}
```diff
${diffText.take(maxDiffChars)}
```
$existingCommentsSection
$relatedPRsSection
        """.trimIndent()
    }
}
