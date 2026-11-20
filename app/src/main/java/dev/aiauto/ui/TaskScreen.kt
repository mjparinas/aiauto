package dev.aiauto.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.aiauto.core.PrState
import dev.aiauto.core.Speaker
import dev.aiauto.core.SpokenStatus
import dev.aiauto.core.Task
import dev.aiauto.core.TaskMessage
import dev.aiauto.core.TaskStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(
    task: Task,
    running: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onMerge: () -> Unit,
    onSend: (String) -> Unit,
) {
    val context = LocalContext.current
    var confirmMerge by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmMerge) task.prNumber?.let { ConfirmMergeDialog(it, task.checks, onConfirm = onMerge, onDismiss = { confirmMerge = false }) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear this task?") },
            text = { Text(if (running) "Claude will be stopped. Its pull request stays on GitHub." else "Its pull request stays on GitHub.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    val list = rememberLazyListState()
    LaunchedEffect(task.messages.size) { if (task.messages.isNotEmpty()) list.animateScrollToItem(task.messages.size - 1) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(task.repo.fullName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    StatusPill(task.status, Modifier.padding(end = 4.dp), interrupted = task.interrupted)
                    if (running) {
                        IconButton(onClick = onStop) { Icon(Icons.Rounded.StopCircle, "Stop") }
                    }
                    IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteOutline, "Clear task") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                if (task.status == TaskStatus.Working || task.status == TaskStatus.Starting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                }
                if (task.status == TaskStatus.Failed) StoppedBanner(task, onRetry)
                PromptBar(placeholder = "Ask for a change…", onSend = onSend)
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = list,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            task.prUrl?.let { url ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AssistChip(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                            label = { Text("PR #${task.prNumber}") },
                            trailingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                        )
                        when (task.prState) {
                            PrState.Merged -> Text("Merged", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            PrState.Closed -> Text("Closed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> {
                                ChecksLabel(task.checks, Modifier.weight(1f))
                                TextButton(onClick = { confirmMerge = true }) { Text("Merge") }
                            }
                        }
                    }
                }
            }
            if (task.costUsd > 0) {
                item {
                    Text(
                        "Cost so far: ${SpokenStatus.money(task.costUsd)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(task.messages, key = { it.id }) { Message(it) }
        }
    }
}

/** Says why the task stopped and offers to pick it back up. */
@Composable
private fun StoppedBanner(task: Task, onRetry: () -> Unit) {
    val reason = task.messages.lastOrNull { it.speaker == Speaker.Status }?.text
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (task.interrupted) "Connection lost. Claude may still be working." else reason ?: "This task stopped.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun Message(message: TaskMessage) {
    when (message.speaker) {
        Speaker.You -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Speaker.Claude -> Text(
            message.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(end = 24.dp),
        )
        Speaker.Status -> Text(
            message.text,
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
