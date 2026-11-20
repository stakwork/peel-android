package com.sphinx.peel.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sphinx.peel.BuildConfig
import com.sphinx.peel.update.ReleaseNotes
import com.sphinx.peel.update.UpdateState
import com.sphinx.peel.update.UpdateText
import com.sphinx.peel.update.Updates

/// Hosted once beside the tabs (MainActivity): the launch check, the "Update / Later" dialog, download progress and the
/// unknown-sources step. Shows nothing while there is nothing to say.
@Composable
fun UpdateHost() {
    val controller = Updates.get(LocalContext.current)
    val state by controller.state.collectAsState()
    val prompt by controller.prompt.collectAsState()

    // Once per process, after unlock (this is composed with the main tabs); the controller ignores it on builds that can't update.
    LaunchedEffect(Unit) { controller.checkOnLaunch() }

    // Back from the unknown-sources screen (or from the installer): continue or offer again.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) controller.resume() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    when (val s = state) {
        is UpdateState.Available -> if (prompt) AlertDialog(
            onDismissRequest = controller::later,
            title = { Text("Peel ${s.release.version.name} is available") },
            text = {
                Column {
                    Text("You have ${BuildConfig.VERSION_NAME}.", style = MaterialTheme.typography.bodySmall)
                    val notes = ReleaseNotes.plain(s.release.notes)
                    if (notes.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                            Text(notes, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = controller::update) { Text("Update") } },
            dismissButton = { TextButton(onClick = controller::later) { Text("Later") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading ${s.release.version.name}") },
            text = {
                Column {
                    val f = UpdateText.fraction(s.bytes, s.total)
                    if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(UpdateText.progress(s.bytes, s.total), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = controller::cancel) { Text("Cancel") } },
        )
        is UpdateState.NeedsPermission -> AlertDialog(
            onDismissRequest = controller::abandonPermission,
            title = { Text("Allow Peel to install updates") },
            text = { Text("Android needs your OK before Peel can install its own update. Allow \"Install unknown apps\" for Peel on the next screen, then come back; the update continues from here.") },
            confirmButton = { TextButton(onClick = controller::openPermissionSettings) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = controller::abandonPermission) { Text("Not now") } },
        )
        is UpdateState.Failed -> if (s.fromUpdate) AlertDialog(
            onDismissRequest = controller::dismissFailure,
            title = { Text("Update didn't finish") },
            text = { Text(s.failure.message) },
            confirmButton = { TextButton(onClick = controller::dismissFailure) { Text("OK") } },
        )
        else -> Unit
    }
}
