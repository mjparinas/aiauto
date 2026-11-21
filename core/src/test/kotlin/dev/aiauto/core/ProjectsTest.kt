package dev.aiauto.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The project view's refresh, and merging from it. */
class ProjectsTest {
    private val repo = RepoRef("me", "app")
    private var now = 1_000_000L
    private var token: String? = "token"
    private val tokensUsed = mutableListOf<String>()

    private open class FakeGitHub : GitHub("token") {
        var statusCalls = 0
        var fail: Exception? = null
        var mergeResult: MergeResult = MergeResult.Merged
        var mergeError: Exception? = null
        var pr = PullRequest(7, "u", nodeId = "PR_7")
        var autoMergeAllowed = false

        override fun status(repo: RepoRef, maxPulls: Int): RepoStatus {
            statusCalls++
            fail?.let { throw it }
            return RepoStatus(repo, CommitInfo("abc", "Add dark mode", "Mel", 0), ChecksSummary(Checks.Passing, total = statusCalls), emptyList())
        }
        override fun merge(repo: RepoRef, number: Int): MergeResult {
            mergeError?.let { throw it }
            return mergeResult
        }
        override fun pull(repo: RepoRef, number: Int) = pr
        override fun enableAutoMerge(pull: PullRequest) = autoMergeAllowed
    }

    private val github = FakeGitHub()

    private fun projects() = Projects(
        githubToken = { token },
        scope = CoroutineScope(SupervisorJob()),
        githubFactory = { tokensUsed += it; github },
        clock = { now },
        dispatcher = Dispatchers.Unconfined,
    )

    @Test fun loadsARepoStatusWithTheSavedToken() {
        val projects = projects()
        projects.refresh(repo)
        val view = projects.view(repo)
        assertEquals("abc", view.status?.latest?.sha)
        assertFalse(view.loading)
        assertNull(view.error)
        assertEquals(now, view.updatedMillis)
        assertEquals(listOf("token"), tokensUsed)
        assertEquals(view, projects.repos.value[repo.fullName])
    }

    @Test fun asksForATokenWhenThereIsNone() {
        token = null
        val projects = projects()
        projects.refresh(repo)
        assertEquals(RepoView(error = "Add a GitHub token in Settings."), projects.view(repo))
        assertEquals(0, github.statusCalls)
    }

    @Test fun keepsTheLastGoodStatusWhenARefreshFails() {
        val projects = projects()
        projects.refresh(repo)
        val good = projects.view(repo).status
        github.fail = GitHubException(503, "GitHub: unavailable")
        projects.refresh(repo)
        val view = projects.view(repo)
        assertEquals(good, view.status)
        assertFalse(view.loading)
        assertEquals("GitHub is having trouble right now.", view.error)

        github.fail = null
        projects.refresh(repo)
        assertNull(projects.view(repo).error)
    }

    @Test fun skipsARefreshWhileTheStatusIsFresh() {
        val projects = projects()
        assertTrue(projects.refresh(repo, maxAgeMillis = 60_000) != null)
        now += 30_000
        assertNull(projects.refresh(repo, maxAgeMillis = 60_000))
        assertEquals(1, github.statusCalls)
        now += 31_000
        projects.refreshAll(listOf(repo), maxAgeMillis = 60_000)
        assertEquals(2, github.statusCalls)
        // A plain refresh always reloads.
        projects.refresh(repo)
        assertEquals(3, github.statusCalls)
    }

    @Test fun aFailedLoadIsRetriedEvenWithAMaxAge() {
        github.fail = GitHubException(500, "GitHub: oops")
        val projects = projects()
        projects.refresh(repo, maxAgeMillis = 60_000)
        github.fail = null
        projects.refresh(repo, maxAgeMillis = 60_000)
        assertEquals(2, github.statusCalls)
        assertTrue(projects.view(repo).status != null)
    }

    @Test fun mergingReloadsTheRepo() {
        val projects = projects()
        assertEquals("Merged pull request 7.", projects.merge(repo, 7))
        assertEquals(1, github.statusCalls)
    }

    @Test fun mergingWithoutATokenSaysSo() {
        token = null
        assertEquals("Add a GitHub token in Settings.", projects().merge(repo, 7))
    }

    @Test fun mergeMessagesForEachOutcome() {
        github.mergeResult = MergeResult.AutoMergeOn
        assertEquals("GitHub will merge pull request 7 when checks pass.", Projects.mergeMessage(github, repo, 7))

        github.mergeResult = MergeResult.Refused("Pull Request is not mergeable")
        github.pr = github.pr.copy(merged = true, state = "closed")
        assertEquals("Pull request 7 is already merged.", Projects.mergeMessage(github, repo, 7))

        github.mergeError = GitHubException(401, "GitHub: Bad credentials")
        assertEquals("Couldn't merge pull request 7. GitHub rejected your token. Update it in Settings.", Projects.mergeMessage(github, repo, 7))
    }
}
