package dev.aiauto.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aiauto.core.PullStatus
import dev.aiauto.core.RepoRef
import dev.aiauto.core.RepoView
import dev.aiauto.core.SpokenStatus

/** Each repo's main branch, latest change and open pull requests, straight from GitHub. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    repos: List<RepoRef>,
    views: Map<String, RepoView>,
    message: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onMerge: (RepoRef, Int) -> Unit,
) {
    var confirm by remember { mutableStateOf<Pair<RepoRef, PullStatus>?>(null) }
    confirm?.let { (repo, p) ->
        ConfirmMergeDialog(p.pull.number, p.checks, onConfirm = { onMerge(repo, p.pull.number) }, onDismiss = { confirm = null })
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = { Text("Projects", style = MaterialTheme.typography.titleLarge) },
                actions = { IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Refresh") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) } }
            if (repos.isEmpty()) item { Text("Add a repo in Settings to see it here.", style = MaterialTheme.typography.bodyLarge) }
            items(repos, key = { it.fullName }) { repo ->
                RepoCard(repo, views[repo.fullName] ?: RepoView(loading = true)) { confirm = repo to it }
            }
        }
    }
}

@Composable
private fun RepoCard(repo: RepoRef, view: RepoView, onMerge: (PullStatus) -> Unit) {
    val context = LocalContext.current
    val open = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(repo.fullName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable { open(repo.url) })
            if (view.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
            view.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            val status = view.status ?: return@Column
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(repo.defaultBranch, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                ChecksLabel(status.branchChecks)
            }
            status.latest?.let { c ->
                Text(
                    c.message + (c.timeMillis?.let { " · " + SpokenStatus.ago(System.currentTimeMillis() - it) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
            if (status.pulls.isEmpty()) {
                Text("No open pull requests", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            status.pulls.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { open(p.pull.htmlUrl) }.padding(vertical = 4.dp)) {
                        Text("#${p.pull.number} ${p.pull.title}", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row {
                            if (p.pull.draft) Text("Draft · ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ChecksLabel(p.checks)
                            if (p.pull.hasGitHubAutoMerge) Text(" · auto-merge on", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!p.pull.draft) TextButton(onClick = { onMerge(p) }) { Text("Merge") }
                }
            }
        }
    }
}
