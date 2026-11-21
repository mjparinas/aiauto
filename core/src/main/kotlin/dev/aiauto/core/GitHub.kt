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
    /** Null while GitHub is still computing it. */
    val mergeable: Boolean? = null,
    val head: Head? = null,
) {
    @Serializable data class Head(val sha: String)
}

enum class CiState { None, Pending, Passed, Failed }

/** The GitHub Actions result for one commit, with a sentence that can be read aloud. */
data class CiResult(val state: CiState, val summary: String = "", val details: String = "")

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
    private data class Runs(@SerialName("workflow_runs") val runs: List<Run> = emptyList()) {
        @Serializable data class Run(
            val id: Long,
            val name: String = "",
            val status: String = "",
            val conclusion: String? = null,
            @SerialName("html_url") val htmlUrl: String = "",
        )
    }

    @Serializable
    private data class Jobs(val jobs: List<Job> = emptyList()) {
        @Serializable data class Job(val name: String = "", val conclusion: String? = null, val steps: List<Step> = emptyList())
        @Serializable data class Step(val name: String = "", val conclusion: String? = null)
    }

    @Serializable
    private data class MergeResult(val merged: Boolean = false, val message: String = "")

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

    /** Merges a pull request with the repo's default merge method. */
    fun merge(repo: RepoRef, number: Int) {
        val result = json.decodeFromString<MergeResult>(send("PUT", "/repos/${repo.fullName}/pulls/$number/merge", "{}"))
        if (!result.merged) throw GitHubException(405, result.message.ifBlank { "GitHub didn't merge the pull request." })
    }

    /** Summarises the GitHub Actions runs for commit [sha]. */
    fun ci(repo: RepoRef, sha: String): CiResult {
        val runs = json.decodeFromString<Runs>(get("/repos/${repo.fullName}/actions/runs?head_sha=$sha&per_page=50")).runs
            // A re-run creates a new run with the same name; only the latest one counts.
            .sortedByDescending { it.id }.distinctBy { it.name }
        if (runs.isEmpty()) return CiResult(CiState.None)
        if (runs.any { it.status != "completed" }) return CiResult(CiState.Pending)
        val failed = runs.filter { it.conclusion !in OK_CONCLUSIONS }
        if (failed.isEmpty()) return CiResult(CiState.Passed, if (runs.size == 1) "The ${runs[0].name} check passed" else "All ${runs.size} checks passed")
        val run = failed.first()
        val jobs = json.decodeFromString<Jobs>(get("/repos/${repo.fullName}/actions/runs/${run.id}/jobs")).jobs
        val job = jobs.firstOrNull { it.conclusion !in OK_CONCLUSIONS }
        val step = job?.steps?.firstOrNull { it.conclusion !in OK_CONCLUSIONS }
        val summary = "The ${run.name} check failed" + (step?.let { " in the ${it.name} step" } ?: "")
        val details = buildString {
            failed.forEach { r ->
                append("Workflow \"${r.name}\" ended with ${r.conclusion}. ${r.htmlUrl}\n")
            }
            if (job != null) append("Failing job: ${job.name}\n")
            if (step != null) append("Failing step: ${step.name}\n")
        }
        return CiResult(CiState.Failed, summary, details)
    }

    private fun get(path: String) = send("GET", path, null)

    private companion object {
        val OK_CONCLUSIONS = setOf("success", "skipped", "neutral")
    }

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
}
