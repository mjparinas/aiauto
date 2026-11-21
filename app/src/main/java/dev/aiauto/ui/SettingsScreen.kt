package dev.aiauto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aiauto.core.RepoRef
import dev.aiauto.core.SpokenStatus

data class SettingsState(
    val hasAnthropicKey: Boolean,
    val hasGithubToken: Boolean,
    val repos: List<RepoRef>,
    val readAloud: Boolean,
    val quietMode: Boolean,
    val autoMerge: Boolean,
    val autoFixChecks: Boolean,
    val autoFixConflicts: Boolean,
    val autoRelease: Boolean,
    val dailyLimitUsd: Double?,
    val spentTodayUsd: Double,
    val version: String,
    val themeMode: ThemeMode,
    val accent: Accent,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * Settings, in the grouped style of Melvin's Tuned app: small capital headers over rounded cards, one row per setting
 * with a tinted round icon badge, the whole row tappable. Anything typed (keys, a repo, the daily limit) opens a dialog
 * from its row rather than sitting on the page as a text field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState,
    onBack: () -> Unit,
    onSaveKeys: (anthropic: String, github: String) -> Unit,
    onAddRepo: (String) -> Unit,
    onReadAloud: (Boolean) -> Unit,
    onQuietMode: (Boolean) -> Unit,
    onAutoMerge: (Boolean) -> Unit,
    onAutoFixChecks: (Boolean) -> Unit,
    onAutoFixConflicts: (Boolean) -> Unit,
    onAutoRelease: (Boolean) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onAccent: (Accent) -> Unit,
    onDailyLimit: (Double?) -> Unit,
    onCheckUpdate: () -> Unit,
) {
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

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
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // Up top, so the result of a dialog's Save is in view without scrolling.
            state.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }

            SectionHeader("Accounts")
            SettingsCard {
                SettingRow(
                    Icons.Rounded.Key, BadgeHue.Amber, "Claude API key",
                    if (state.hasAnthropicKey) "Saved" else "Not set. Create one at platform.claude.com.",
                    enabled = !state.busy,
                ) { dialog = SettingsDialog.AnthropicKey }
                SettingRow(
                    Icons.Rounded.Code, BadgeHue.Violet, "GitHub token",
                    if (state.hasGithubToken) "Saved" else "Not set",
                    enabled = !state.busy,
                ) { dialog = SettingsDialog.GithubToken }
            }

            SectionHeader("Repositories")
            SettingsCard {
                state.repos.forEach { repo -> SettingRow(Icons.Rounded.FolderOpen, BadgeHue.Blue, repo.fullName) }
                SettingRow(
                    Icons.Rounded.Add, BadgeHue.Green, "Add a repository",
                    if (state.hasGithubToken) "Enter it as owner/name" else "Save a GitHub token first",
                    enabled = !state.busy && state.hasGithubToken,
                ) { dialog = SettingsDialog.AddRepo }
            }

            SectionHeader("In the car")
            SettingsCard {
                SwitchRow(
                    Icons.AutoMirrored.Rounded.VolumeUp, BadgeHue.Teal, "Read updates aloud",
                    "Speaks each update through the car while Android Auto is connected.",
                    state.readAloud, onReadAloud,
                )
                SwitchRow(
                    Icons.Rounded.NotificationsPaused, BadgeHue.Blue, "Quiet mode",
                    "Only tells you the result of each request, not every step along the way.",
                    state.quietMode, onQuietMode,
                )
            }

            SectionHeader("Pull requests")
            SettingsCard {
                SwitchRow(
                    Icons.Rounded.DoneAll, BadgeHue.Green, "Merge when checks pass",
                    "Turns on GitHub auto-merge when the repo allows it. Otherwise the app merges once every check is green. Failing checks are never merged.",
                    state.autoMerge, onAutoMerge,
                )
                SwitchRow(
                    Icons.Rounded.Sync, BadgeHue.Violet, "Fix merge conflicts automatically",
                    "When a pull request conflicts with the main branch, Claude merges it in, resolves the conflicts and pushes, without asking first.",
                    state.autoFixConflicts, onAutoFixConflicts,
                )
                SwitchRow(
                    Icons.Rounded.Build, BadgeHue.Red, "Fix failing checks automatically",
                    "When tests, the build or any other CI check fails, Claude fixes it and pushes, without asking first. Up to three tries per task before it asks you.",
                    state.autoFixChecks, onAutoFixChecks,
                )
                SwitchRow(
                    Icons.Rounded.NewReleases, BadgeHue.Amber, "Release when a PR is merged",
                    "When a pull request from this app is merged, publishes a GitHub release with the next patch version, like v1.2.3 to v1.2.4.",
                    state.autoRelease, onAutoRelease,
                )
            }

            SectionHeader("Appearance")
            SettingsCard {
                SettingRow(Icons.Rounded.DarkMode, BadgeHue.Violet, "Theme", state.themeMode.label) { dialog = SettingsDialog.Theme }
                AccentRow(state.accent, onAccent)
            }

            SectionHeader("Spending")
            SettingsCard {
                SettingRow(
                    Icons.Rounded.Savings, BadgeHue.Green, "Daily limit",
                    "Spent today: ${SpokenStatus.money(state.spentTodayUsd)}" +
                        (state.dailyLimitUsd?.let { " of ${SpokenStatus.money(it)}" } ?: ", no limit"),
                ) { dialog = SettingsDialog.DailyLimit }
            }
            Note("Claude stops when today's total reaches the limit, counted at list price from what each session reports.")

            SectionHeader("About")
            SettingsCard {
                SettingRow(Icons.Rounded.Info, BadgeHue.Blue, "Cruise Control", "Version ${state.version}")
                SettingRow(
                    Icons.Rounded.SystemUpdate, BadgeHue.Teal, "Check for updates",
                    "Looks for a newer release on GitHub",
                    enabled = !state.busy,
                    onClick = onCheckUpdate,
                )
            }
        }
    }

    when (dialog) {
        SettingsDialog.AnthropicKey -> TextDialog(
            title = "Claude API key",
            help = "Create one at platform.claude.com. Usage is billed to that account.",
            label = "API key",
            secret = true,
            onDismiss = { dialog = null },
        ) { onSaveKeys(it, "") }
        SettingsDialog.GithubToken -> TextDialog(
            title = "GitHub token",
            help = "A fine-grained token with Contents and Pull requests set to read and write, and Checks and Commit statuses set to read, on your repos.",
            label = "Token",
            secret = true,
            onDismiss = { dialog = null },
        ) { onSaveKeys("", it) }
        SettingsDialog.AddRepo -> TextDialog(
            title = "Add a repository",
            label = "owner/repo",
            keyboard = KeyboardType.Uri,
            confirm = "Add",
            onDismiss = { dialog = null },
        ) { onAddRepo(it) }
        SettingsDialog.DailyLimit -> TextDialog(
            title = "Daily limit",
            help = "In US dollars. Leave blank for no limit.",
            label = "Daily limit",
            initial = state.dailyLimitUsd?.let { "%.2f".format(java.util.Locale.US, it).removeSuffix(".00") }.orEmpty(),
            keyboard = KeyboardType.Decimal,
            allowBlank = true,
            filter = { it.filter { c -> c.isDigit() || c == '.' } },
            onDismiss = { dialog = null },
        ) { onDailyLimit(it.toDoubleOrNull()?.takeIf { v -> v > 0 }) }
        SettingsDialog.Theme -> ChoiceDialog(
            title = "Theme",
            options = ThemeMode.entries,
            selected = state.themeMode,
            label = { it.label },
            onDismiss = { dialog = null },
            onSelect = onThemeMode,
        )
        null -> Unit
    }
}

private enum class SettingsDialog { AnthropicKey, GithubToken, AddRepo, DailyLimit, Theme }

/** Small capitals over each card, lined up with the text on the card below it. A heading for screen readers. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge.merge(TextStyle(fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 32.dp)
            .padding(top = 20.dp, bottom = 8.dp)
            .semantics {
                heading()
                contentDescription = text
            },
    )
}

/** One section's rows together on a rounded card; each row's press ripple stays inside the card's corners. */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp),
        content = content,
    )
}

/** A line of small print under a card. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    hue: BadgeHue,
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, hue)
        Spacer(Modifier.width(16.dp))
        RowText(title, summary, Modifier.weight(1f))
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    hue: BadgeHue,
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Toggleable, so a screen reader says "on" or "off", and the whole row flips the switch.
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, hue)
        Spacer(Modifier.width(16.dp))
        RowText(title, summary, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RowText(title: String, summary: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (!summary.isNullOrBlank()) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Accent colour: a row of round swatches, the chosen one ticked. Each shrinks a little on a narrow phone rather than being cut off. */
@Composable
private fun AccentRow(selected: Accent, onPick: (Accent) -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Palette, BadgeHue.Accent)
            Spacer(Modifier.width(16.dp))
            RowText("Accent color", selected.label, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 60.dp),
        ) {
            Accent.entries.forEach { accent ->
                val on = accent == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 40.dp)
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(if (dark) accent.dark else accent.light)
                        .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(accent) })
                        .semantics { contentDescription = accent.label },
                ) {
                    if (on) Icon(Icons.Rounded.Check, null, tint = if (dark) Color(0xFF1A1714) else Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * The colours of a row's round icon badge. Each has a deep shade for the light theme and a soft one for the dark theme,
 * so the icon reads well on its own tinted circle either way. [Accent] follows the chosen accent colour.
 */
private enum class BadgeHue(val light: Long, val dark: Long) {
    Blue(0xFF14509A, 0xFF8AB4F8),
    Violet(0xFF5235C8, 0xFFB9A6FF),
    Teal(0xFF005D52, 0xFF4DD0C2),
    Red(0xFF9E1B1B, 0xFFFF8A80),
    Amber(0xFF7A4700, 0xFFFFC266),
    Green(0xFF1B5E20, 0xFF81C995),
    Accent(0, 0),
}

@Composable
private fun IconBadge(icon: ImageVector, hue: BadgeHue) {
    val colour = when {
        hue == BadgeHue.Accent -> MaterialTheme.colorScheme.primary
        MaterialTheme.colorScheme.background.luminance() < 0.5f -> Color(hue.dark)
        else -> Color(hue.light)
    }
    Box(
        Modifier.size(44.dp).background(colour.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(22.dp))
    }
}

/** A dialog with one text field. Saving with a blank field is allowed only when [allowBlank] (it means "clear"). */
@Composable
private fun TextDialog(
    title: String,
    label: String,
    help: String? = null,
    initial: String = "",
    secret: Boolean = false,
    keyboard: KeyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
    confirm: String = "Save",
    allowBlank: Boolean = false,
    filter: (String) -> String = { it },
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (help != null) {
                    Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = filter(it) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    singleLine = true,
                    label = { Text(label) },
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text.trim()); onDismiss() },
                enabled = allowBlank || text.isNotBlank(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A setting with a fixed set of choices, picked from a list of radio buttons. */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .selectable(selected = option == selected, role = Role.RadioButton, onClick = {
                                onSelect(option)
                                onDismiss()
                            })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
