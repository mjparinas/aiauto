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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
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
    val quietMode: Boolean,
    val autoMerge: Boolean,
    val autoFixChecks: Boolean,
    val autoFixConflicts: Boolean,
    val dailyLimitUsd: Double?,
    val spentTodayUsd: Double,
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
    onQuietMode: (Boolean) -> Unit,
    onAutoMerge: (Boolean) -> Unit,
    onAutoFixChecks: (Boolean) -> Unit,
    onAutoFixConflicts: (Boolean) -> Unit,
    onDailyLimit: (Double?) -> Unit,
) {
    var limitText by remember { mutableStateOf(state.dailyLimitUsd?.let { "%.2f".format(java.util.Locale.US, it).removeSuffix(".00") }.orEmpty()) }
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
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
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
                help = "A fine-grained token with Contents and Pull requests set to read and write, and Checks and Commit statuses set to read, on your repos.",
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Quiet mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Only tells you the result of each request, not every step along the way.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.quietMode, onCheckedChange = onQuietMode)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
            Section("Pull requests")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Merge when checks pass", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Turns on GitHub auto-merge when the repo allows it. Otherwise the app merges once every check is green. Failing checks are never merged.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.autoMerge, onCheckedChange = onAutoMerge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fix merge conflicts automatically", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "When a pull request conflicts with the main branch, Claude merges it in, resolves the conflicts and pushes, without asking first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.autoFixConflicts, onCheckedChange = onAutoFixConflicts)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fix failing checks automatically", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "When tests, the build or any other CI check fails, Claude fixes it and pushes, without asking first. Up to three tries per task before it asks you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.autoFixChecks, onCheckedChange = onAutoFixChecks)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
            Section("Spending")
            Text(
                "Spent today: ${dev.aiauto.core.SpokenStatus.money(state.spentTodayUsd)}" +
                    (state.dailyLimitUsd?.let { " of ${dev.aiauto.core.SpokenStatus.money(it)}" } ?: ", no limit"),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { limitText = it.filter { c -> c.isDigit() || c == '.' } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Daily limit in US dollars") },
                    placeholder = { Text("Blank for no limit") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedButton(onClick = { onDailyLimit(limitText.toDoubleOrNull()?.takeIf { it > 0 }) }) { Text("Set") }
            }
            Text(
                "Claude stops when today's total reaches the limit, counted at list price from what each session reports.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
            singleLine = true,
            label = { Text(if (saved) "$label (saved)" else label) },
            placeholder = { if (saved) Text("Leave blank to keep the saved one") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
