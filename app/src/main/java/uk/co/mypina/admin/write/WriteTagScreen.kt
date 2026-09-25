package uk.co.mypina.admin.write

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import uk.co.mypina.admin.nfc.ResetResult
import uk.co.mypina.admin.nfc.Step
import uk.co.mypina.admin.nfc.StepState
import uk.co.mypina.admin.nfc.TagWriter
import uk.co.mypina.admin.nfc.WriteResult
import uk.co.mypina.admin.ui.Field
import uk.co.mypina.admin.ui.NfcReaderScreen

@Composable
fun WriteTagScreen(
    tagId: String,
    writerFactory: () -> TagWriter,
    onClose: () -> Unit,
) {
    val vm: WriteTagViewModel = viewModel(
        factory = viewModelFactory { initializer { WriteTagViewModel(tagId, writerFactory()) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val resetting = state.mode == Mode.RESET
    var menuOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    // Keys pass through this screen: no screenshots (FLAG_SECURE), and keep it on until Done shows.
    NfcReaderScreen(
        title = if (resetting) "Reset chip" else "Write tag",
        purpose = if (resetting) "reset chips" else "write tags",
        secure = true,
        onTag = vm::onTagDiscovered,
        onClose = onClose,
        actions = {
            if (!resetting) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Reset chip to factory keys…") },
                        enabled = state.phase != Phase.WRITING,
                        onClick = {
                            menuOpen = false
                            confirmReset = true
                        },
                    )
                }
            }
        },
    ) {
        if (state.phase != Phase.DONE) {
            Text("Hold the tag to the back of the phone.", style = MaterialTheme.typography.titleLarge)
            if (state.phase == Phase.WRITING) {
                Text(
                    "Keep it there until Done shows.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        StepList(state.steps)

        if (state.phase == Phase.FAILED) ErrorCard(state)

        state.result?.let { ResultCard(it) }
        state.resetResult?.let { ResetResultCard(it) }

        when {
            state.phase == Phase.DONE ->
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            state.phase == Phase.FAILED && !state.retryable ->
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Back to the website") }
        }
        if (resetting && state.phase != Phase.WRITING && state.phase != Phase.DONE) {
            OutlinedButton(onClick = vm::cancelReset, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset chip to factory keys?") },
            text = {
                Text(
                    "This puts keys 0, 1 and 2 back to factory zeros so another server can write the chip. " +
                        "The tag row on this server is not changed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.startReset()
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StepList(steps: Map<Step, StepState>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            steps.forEach { (step, s) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StepIcon(s)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        step.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = when (s) {
                            StepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                            StepState.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        fontWeight = if (s == StepState.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepIcon(state: StepState) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (state) {
            StepState.PENDING -> Box(
                Modifier
                    .size(16.dp)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
            StepState.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            StepState.DONE -> Icon(Icons.Filled.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)
            StepState.FAILED -> Icon(Icons.Filled.Close, contentDescription = "Failed", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ErrorCard(state: WriteState) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Failed at: ${state.failedStep?.label ?: "unknown step"}",
                style = MaterialTheme.typography.titleMedium,
            )
            state.error?.let { Text(it) }
            if (state.retryable) Text("Hold the tag again to continue.", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ResultCard(result: WriteResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Tag written", style = MaterialTheme.typography.titleMedium)
            Field("Tag", result.tagCode)
            Field("Shop", result.shopName)
            Field("UID", result.uid, mono = true)
            Field("Counter", result.counter.toString())
        }
    }
}

@Composable
private fun ResetResultCard(result: ResetResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Chip reset to factory keys", style = MaterialTheme.typography.titleMedium)
            Field("UID", result.uid, mono = true)
        }
    }
}
