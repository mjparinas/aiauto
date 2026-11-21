package dev.aiauto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.aiauto.core.RepoRef

data class SettingsState(
    val hasAnthropicKey: Boolean,
    val hasGithubToken: Boolean,
    val repos: List<RepoRef>,
    val readAloud: Boolean,
    val busy: Boolean = false,
    val message: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState,
    onBack: () -> Unit,
    onSaveKeys: (anthropic: String, github: String) -> Unit,
    onAddRepo: (String) -> Unit,
    onRemoveRepo: (RepoRef) -> Unit,
    onReadAloud: (Boolean) -> Unit,
) {
    var anthropic by remember { mutableStateOf("") }
    var github by remember { mutableStateOf("") }
    var repoText by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Accounts")
            SecretField(
                label = "Claude API key",
                saved = state.hasAnthropicKey,
                value = anthropic,
                onChange = { anthropic = it },
                help = "Create one at platform.claude.com. Usage is billed to that account.",
            )
            SecretField(
                label = "GitHub token",
                saved = state.hasGithubToken,
                value = github,
                onChange = { github = it },
                help = "A fine-grained token with Contents and Pull requests set to read and write on your repos.",
            )
            Button(
                onClick = { onSaveKeys(anthropic, github); anthropic = ""; github = "" },
                enabled = !state.busy && (anthropic.isNotBlank() || github.isNotBlank()),
            ) { Text("Save") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
            Section("Repositories")
            state.repos.forEach { repo ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(repo.fullName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemoveRepo(repo) }) { Icon(Icons.Rounded.Close, "Remove") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = repoText,
                    onValueChange = { repoText = it },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                    singleLine = true,
                    label = { Text("owner/repo") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedButton(
                    onClick = { onAddRepo(repoText); repoText = "" },
                    enabled = !state.busy && repoText.isNotBlank() && state.hasGithubToken,
                ) { Text("Add") }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
            Section("In the car")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Read updates aloud", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Speaks each update through the car while Android Auto is connected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.readAloud, onCheckedChange = onReadAloud)
            }

            state.message?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SecretField(label: String, saved: Boolean, value: String, onChange: (String) -> Unit, help: String) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            singleLine = true,
            label = { Text(if (saved) "$label (saved)" else label) },
            placeholder = { if (saved) Text("Leave blank to keep the saved one") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
