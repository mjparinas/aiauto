package dev.aiauto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.aiauto.core.RepoRef

@Composable
fun NewTaskScreen(repos: List<RepoRef>, lastUsed: RepoRef?, onBack: () -> Unit, onStart: (RepoRef, String) -> Unit) {
    var selected by remember { mutableStateOf(lastUsed?.takeIf { it in repos } ?: repos.firstOrNull()) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar("New task", onBack) },
        bottomBar = {
            Column(Modifier.bottomBarInsets()) {
                PromptBar(placeholder = "What should Claude do?", enabled = selected != null) { text ->
                    selected?.let { onStart(it, text) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SectionHeader("Repository")
            SettingsCard {
                repos.forEach { repo -> RepoChoice(repo, selected = repo == selected) { selected = repo } }
            }

            SectionHeader("How it works")
            SettingsCard {
                SettingRow(Icons.Rounded.AccountTree, BadgeHue.Violet, "Works on a new branch", "Describe the change in a sentence or two.")
                SettingRow(Icons.Rounded.Science, BadgeHue.Teal, "Runs the tests it finds", "Before it opens the pull request.")
                SettingRow(Icons.Rounded.Visibility, BadgeHue.Green, "Opens a pull request", "For you to review. Updates arrive as messages you can hear in the car.")
            }
        }
    }
}

/** One repository to pick, as a radio row on the card. */
@Composable
private fun RepoChoice(repo: RepoRef, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(Icons.Rounded.FolderOpen, if (selected) BadgeHue.Accent else BadgeHue.Blue)
        Spacer(Modifier.width(16.dp))
        RowText(repo.name, repo.owner, Modifier.weight(1f))
        RadioButton(selected = selected, onClick = null)
    }
}
