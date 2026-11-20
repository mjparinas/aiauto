package dev.aiauto.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aiauto.core.TaskStatus

@Composable
fun StatusPill(status: TaskStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        TaskStatus.Starting -> "Starting" to MaterialTheme.colorScheme.onSurfaceVariant
        TaskStatus.Working -> "Working" to Clay
        TaskStatus.NeedsInput -> "Needs you" to Color(0xFFB7791F)
        TaskStatus.Done -> "Done" to Color(0xFF4F7A55)
        TaskStatus.Failed -> "Stopped" to MaterialTheme.colorScheme.error
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** A prompt box with a send button and a mic button that uses the phone's speech recognizer. */
@Composable
fun PromptBar(placeholder: String, enabled: Boolean = true, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) text = if (text.isBlank()) spoken else "$text $spoken"
        }
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                shape = MaterialTheme.shapes.large,
                maxLines = 5,
                enabled = enabled,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                trailingIcon = {
                    IconButton(enabled = enabled, onClick = {
                        voice.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, placeholder),
                        )
                    }) { Icon(Icons.Rounded.Mic, contentDescription = "Speak") }
                },
            )
            FilledIconButton(
                enabled = enabled && text.isNotBlank(),
                onClick = { onSend(text.trim()); text = "" },
            ) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send") }
        }
    }
}
