package dev.aiauto.ui

import android.text.format.DateUtils
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.FolderOpen
import dev.aiauto.core.Checks
import dev.aiauto.core.PrState
import dev.aiauto.core.SpokenStatus
import dev.aiauto.core.Task

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    tasks: List<Task>,
    hasRepos: Boolean,
    onOpen: (Task) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onProjects: () -> Unit,
    finishedCount: Int,
    onClearFinished: () -> Unit,
    spentToday: Double,
    dailyLimit: Double?,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Tasks", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    if (finishedCount > 0) IconButton(onClick = onClearFinished) { Icon(Icons.Rounded.ClearAll, "Clear $finishedCount finished") }
                    IconButton(onClick = onProjects) { Icon(Icons.Rounded.FolderOpen, "Projects") }
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = if (hasRepos) onNew else onSettings,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(if (hasRepos) "New task" else "Add a repo") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        if (tasks.isEmpty()) {
            EmptyState(Modifier.padding(padding), hasRepos)
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Today: ${SpokenStatus.money(spentToday)}" + (dailyLimit?.let { " of ${SpokenStatus.money(it)} limit" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(tasks, key = { it.id }) { task -> TaskCard(task) { onOpen(task) } }
            }
        }
    }
}

@Composable
private fun TaskCard(task: Task, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.repo.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(task.status, interrupted = task.interrupted)
            }
            Spacer(Modifier.height(6.dp))
            Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            task.lastMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Text(
                    DateUtils.getRelativeTimeSpanString(task.updatedMillis).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (task.costUsd > 0) {
                    Text(
                        SpokenStatus.money(task.costUsd),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                task.prNumber?.let {
                    Text(
                        when (task.prState) {
                            PrState.Merged -> "PR #$it merged"
                            PrState.Closed -> "PR #$it closed"
                            else -> "PR #$it · " + SpokenStatus.checks(task.checks)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (task.checks?.state == Checks.Failing && task.prState != PrState.Merged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, hasRepos: Boolean) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing running yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasRepos) "Start a task by typing or speaking a request. In the car, pick a repo and reply by voice."
            else "Add your Claude API key, a GitHub token and a repo in Settings to get started.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
