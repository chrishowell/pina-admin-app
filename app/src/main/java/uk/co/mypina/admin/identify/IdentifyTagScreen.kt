package uk.co.mypina.admin.identify

import android.nfc.tech.IsoDep
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import uk.co.mypina.admin.api.IdentifyResponse
import uk.co.mypina.admin.api.TagSummary
import uk.co.mypina.admin.ui.Field
import uk.co.mypina.admin.ui.NfcReaderScreen
import uk.co.mypina.admin.verify.formatDate

/**
 * Identify tag: reads any chip without keys (UID, then the URL if it has one) and asks the server
 * what it is: which tag row the UID is bound to, and what the URL says and whether it verifies.
 */
@Composable
fun IdentifyTagScreen(
    readChip: (IsoDep) -> ChipRead,
    identify: (uid: String, url: String?) -> IdentifyResponse,
    onClose: () -> Unit,
) {
    val vm: IdentifyTagViewModel = viewModel(
        factory = viewModelFactory { initializer { IdentifyTagViewModel(readChip, identify) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // No keys on this screen, so no FLAG_SECURE.
    NfcReaderScreen(
        title = "Identify tag",
        purpose = "identify tags",
        secure = false,
        onTag = vm::onTagDiscovered,
        onClose = onClose,
    ) {
        when (state.phase) {
            IdentifyPhase.WAITING ->
                Text("Hold any tag to the back of the phone.", style = MaterialTheme.typography.titleLarge)
            IdentifyPhase.READING -> Busy("Reading the chip…")
            IdentifyPhase.CHECKING -> Busy("Asking the server…")
            IdentifyPhase.FAILED -> ErrorCard(state.error.orEmpty(), state.uid, state.url)
            IdentifyPhase.DONE -> {
                val r = state.result
                val v = state.verdict
                if (r != null && v != null) ResultCard(r, v, state.url)
            }
        }

        if (state.phase == IdentifyPhase.DONE || state.phase == IdentifyPhase.FAILED) {
            Text(
                "Hold another tag to the phone to identify it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) { Text("Scan another") }
        }
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ErrorCard(message: String, uid: String?, url: String?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Couldn't identify", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(message)
            uid?.let { Field("UID", it, mono = true) }
            url?.let { UrlLine(it) }
        }
    }
}

@Composable
private fun ResultCard(r: IdentifyResponse, v: IdentifyVerdict, url: String?) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (v.tone) {
        Tone.GOOD -> scheme.primaryContainer to scheme.onPrimaryContainer
        Tone.NEUTRAL -> scheme.secondaryContainer to scheme.onSecondaryContainer
        Tone.WARN -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        Tone.BAD -> scheme.errorContainer to scheme.onErrorContainer
    }
    val icon = when (v.tone) {
        Tone.GOOD -> Icons.Filled.CheckCircle
        Tone.NEUTRAL -> Icons.Filled.Info
        Tone.WARN, Tone.BAD -> Icons.Filled.Warning
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Text(v.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            v.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val w = 128.dp
            v.tag?.let { TagFields(it, labelWidth = w) }
            v.counter?.let { Field("Chip counter", it, labelWidth = w) }
            if (v.tag != null || v.counter != null) HorizontalDivider()
            Field("UID", r.uid, mono = true, labelWidth = w)
            url?.let { UrlLine(it) }
        }
    }
}

@Composable
private fun TagFields(t: TagSummary, labelWidth: Dp) {
    Field("Tag", t.code, labelWidth = labelWidth)
    Field("Shop", t.shopName, labelWidth = labelWidth)
    t.label?.takeIf { it.isNotBlank() }?.let { Field("Label", it, labelWidth = labelWidth) }
    Field("Shop status", t.shopStatus, labelWidth = labelWidth)
    Field("Status", if (t.active) "Active" else "Off", labelWidth = labelWidth)
    Field("Written on", formatDate(t.encodedAt), labelWidth = labelWidth)
    Field("Server counter", t.lastCounter.toString(), labelWidth = labelWidth)
    Field("Key version", t.keyVersion?.toString() ?: "none", labelWidth = labelWidth)
}

@Composable
private fun UrlLine(url: String) {
    Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}
