package dev.aiauto.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Tells the user a new version is out and installs it if they agree. Shows nothing otherwise. */
@Composable
fun UpdateDialog(updater: Updater) {
    val state by updater.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is Updater.State.Idle -> Unit
        is Updater.State.Available -> AlertDialog(
            onDismissRequest = updater::later,
            title = { Text("Update available") },
            text = { Text("Cruise Control ${s.update.version} is ready. You have ${updater.currentVersion}.") },
            confirmButton = { TextButton(onClick = { updater.install(s.update) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = updater::later) { Text("Later") } },
        )
        is Updater.State.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading ${s.update.version}") },
            text = {
                Column {
                    Text("The installer opens when the download finishes.")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {},
        )
        is Updater.State.Failed -> AlertDialog(
            onDismissRequest = updater::later,
            title = { Text("Update failed") },
            text = { Text(s.reason) },
            confirmButton = { TextButton(onClick = { updater.install(s.update) }) { Text("Try again") } },
            dismissButton = { TextButton(onClick = updater::later) { Text("Later") } },
        )
    }
}
