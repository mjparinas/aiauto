package dev.aiauto.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class GitHubException(val code: Int, message: String) : IOException(message)

@Serializable
data class PullRequest(
    val number: Int,
    @SerialName("html_url") val htmlUrl: String,
    val state: String = "open",
    val title: String = "",
    val merged: Boolean = false,
    @SerialName("merged_at") val mergedAt: String? = null,
    @SerialName("merge_commit_sha") val mergeCommitSha: String? = null,
)

@Serializable
data class Release(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
)

/** The few GitHub REST calls the app needs, authenticated with the user's token. */
class GitHub(private val token: String, private val http: OkHttpClient = OkHttpClient()) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class RepoJson(
        val name: String,
        @SerialName("full_name") val fullName: String,
        @SerialName("default_branch") val defaultBranch: String,
        val owner: Owner,
        val permissions: Permissions? = null,
    ) {
        @Serializable data class Owner(val login: String)
        @Serializable data class Permissions(val push: Boolean = false)

        fun toRef() = RepoRef(owner.login, name, defaultBranch)
    }

    @Serializable
    private data class TagJson(val name: String)

    @Serializable
    private data class NewRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("target_commitish") val target: String,
        val name: String,
        @SerialName("generate_release_notes") val generateNotes: Boolean = true,
    )

    @Serializable
    private data class NewPull(val title: String, val head: String, val base: String, val body: String)

    /** Looks up a repo, confirming the token can see it and getting its default branch. */
    fun repo(owner: String, name: String): RepoRef {
        val r = json.decodeFromString<RepoJson>(get("/repos/$owner/$name"))
        if (r.permissions?.push == false) throw GitHubException(403, "Your token can read ${r.fullName} but can't push to it.")
        return r.toRef()
    }

    /** Repos the token can push to, most recently pushed first. */
    fun myRepos(): List<RepoRef> =
        json.decodeFromString<List<RepoJson>>(get("/user/repos?sort=pushed&per_page=50"))
            .filter { it.permissions?.push != false }
            .map { it.toRef() }

    fun branchExists(repo: RepoRef, branch: String): Boolean = try {
        get("/repos/${repo.fullName}/branches/$branch")
        true
    } catch (e: GitHubException) {
        if (e.code == 404) false else throw e
    }

    fun findPull(repo: RepoRef, branch: String): PullRequest? =
        json.decodeFromString<List<PullRequest>>(get("/repos/${repo.fullName}/pulls?state=all&head=${repo.owner}:$branch"))
            .firstOrNull()

    fun openPull(repo: RepoRef, branch: String, title: String, body: String): PullRequest {
        val payload = json.encodeToString(NewPull.serializer(), NewPull(title, branch, repo.defaultBranch, body))
        return json.decodeFromString(send("POST", "/repos/${repo.fullName}/pulls", payload))
    }

    fun pull(repo: RepoRef, number: Int): PullRequest =
        json.decodeFromString(get("/repos/${repo.fullName}/pulls/$number"))

    fun tags(repo: RepoRef): List<String> =
        json.decodeFromString<List<TagJson>>(get("/repos/${repo.fullName}/tags?per_page=100")).map { it.name }

    /** Publishes a release, creating [tag] at [target] with GitHub's generated notes. */
    fun createRelease(repo: RepoRef, tag: String, target: String): Release {
        val payload = json.encodeToString(NewRelease.serializer(), NewRelease(tag, target, tag))
        return json.decodeFromString(send("POST", "/repos/${repo.fullName}/releases", payload))
    }

    private fun get(path: String) = send("GET", path, null)

    private fun send(method: String, path: String, body: String?): String {
        val request = Request.Builder()
            .url("https://api.github.com$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(text)["message"]?.toString()?.trim('"') }
                    .getOrNull() ?: "HTTP ${response.code}"
                throw GitHubException(response.code, "GitHub: $message")
            }
            return text
        }
    }

    companion object {
        private val version = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")

        /** The next patch version after the highest semver tag, keeping its v prefix. v0.1.0 when there are none. */
        fun nextVersion(tags: List<String>): String {
            val latest = tags.mapNotNull { tag -> version.matchEntire(tag)?.let { tag to it.groupValues.drop(1).map(String::toInt) } }
                .maxWithOrNull(compareBy({ it.second[0] }, { it.second[1] }, { it.second[2] }))
                ?: return "v0.1.0"
            val (major, minor, patch) = latest.second
            val prefix = if (latest.first.startsWith("v")) "v" else ""
            return "$prefix$major.$minor.${patch + 1}"
        }
    }
}
