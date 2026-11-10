package dev.pocketreviewer.util

import kotlinx.serialization.Serializable

@Serializable
data class PullRequest(
    val url: String,
    val owner: String,
    val repo: String,
    val number: Int,
    val key: String
) {
    companion object {
        fun fromKey(key: String, url: String): PullRequest {
            // key format: "owner/repo#123"
            val (ownerRepo, numberStr) = key.split("#")
            val (owner, repo) = ownerRepo.split("/")
            return PullRequest(
                url = url,
                owner = owner,
                repo = repo,
                number = numberStr.toInt(),
                key = key
            )
        }
    }
}
