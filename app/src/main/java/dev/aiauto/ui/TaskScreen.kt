package dev.aiauto.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import dev.aiauto.core.Speaker
import dev.aiauto.core.Task
import dev.aiauto.core.TaskMessage
import dev.aiauto.core.TaskStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(task: Task, onBack: () -> Unit, onSend: (String) -> Unit) {
    val context = LocalContext.current
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
                actions = { StatusPill(task.status, Modifier.padding(end = 12.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                if (task.status == TaskStatus.Working || task.status == TaskStatus.Starting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                }
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
                    AssistChip(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                        label = { Text("Pull request #${task.prNumber} on GitHub") },
                        trailingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                    )
                }
            }
            items(task.messages, key = { it.id }) { Message(it) }
        }
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
