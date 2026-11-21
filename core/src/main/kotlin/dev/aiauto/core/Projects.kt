package dev.aiauto.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** What the project view shows for one repo: the last good status, plus whether a refresh is running or failed. */
data class RepoView(
    val status: RepoStatus? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val updatedMillis: Long = 0,
)

/**
 * The project view: each registered repo's latest commit, branch checks and open pull requests,
 * straight from GitHub, so it also covers work that didn't start in this app.
 */
class Projects(
    private val githubToken: () -> String?,
    private val scope: CoroutineScope,
    private val githubFactory: (String) -> GitHub = { GitHub(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineContext = Dispatchers.IO,
) {
    private val views = MutableStateFlow<Map<String, RepoView>>(emptyMap())
    val repos: StateFlow<Map<String, RepoView>> = views

    fun view(repo: RepoRef): RepoView = views.value[repo.fullName] ?: RepoView()

    /** Reloads [repo] unless it was loaded in the last [maxAgeMillis]. */
    fun refresh(repo: RepoRef, maxAgeMillis: Long = 0): Job? {
        val current = view(repo)
        if (current.loading || (maxAgeMillis > 0 && current.status != null && clock() - current.updatedMillis < maxAgeMillis)) return null
        set(repo) { it.copy(loading = true, error = null) }
        return scope.launch(dispatcher) {
            val token = githubToken()
            if (token == null) {
                set(repo) { it.copy(loading = false, error = "Add a GitHub token in Settings.") }
                return@launch
            }
            val result = runCatching { githubFactory(token).status(repo) }
            set(repo) { old ->
                result.fold(
                    { RepoView(status = it, updatedMillis = clock()) },
                    { old.copy(loading = false, error = Failures.describe(it)) },
                )
            }
        }
    }

    fun refreshAll(repos: List<RepoRef>, maxAgeMillis: Long = 0) = repos.forEach { refresh(it, maxAgeMillis) }

    /** Merges a pull request at the user's request and reloads the repo. Returns a sentence saying what happened. Blocking. */
    fun merge(repo: RepoRef, number: Int): String {
        val token = githubToken() ?: return "Add a GitHub token in Settings."
        val message = mergeMessage(githubFactory(token), repo, number)
        refresh(repo)
        return message
    }

    private fun set(repo: RepoRef, transform: (RepoView) -> RepoView) =
        views.update { it + (repo.fullName to transform(it[repo.fullName] ?: RepoView())) }

    companion object {
        /**
         * Merges [number] now. If GitHub won't merge yet because required checks are still running,
         * turns on GitHub's auto-merge instead so it merges by itself once they pass.
         */
        fun mergeMessage(github: GitHub, repo: RepoRef, number: Int): String = try {
            when (val result = github.merge(repo, number)) {
                MergeResult.Merged -> "Merged pull request $number."
                MergeResult.AutoMergeOn -> "GitHub will merge pull request $number when checks pass."
                is MergeResult.Refused -> {
                    val pull = runCatching { github.pull(repo, number) }.getOrNull()
                    when {
                        pull?.prState == PrState.Merged -> "Pull request $number is already merged."
                        pull != null && runCatching { github.enableAutoMerge(pull) }.getOrDefault(false) ->
                            "Checks are still running, so I turned on auto-merge. GitHub will merge pull request $number when they pass."
                        else -> "Couldn't merge pull request $number. ${result.reason}"
                    }
                }
            }
        } catch (e: Exception) {
            "Couldn't merge pull request $number. ${Failures.describe(e)}"
        }
    }
}
