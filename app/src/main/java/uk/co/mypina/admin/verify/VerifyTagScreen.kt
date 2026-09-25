package uk.co.mypina.admin.verify

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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import uk.co.mypina.admin.api.VerifyResponse
import uk.co.mypina.admin.ui.Field
import uk.co.mypina.admin.ui.NfcReaderScreen
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Verify tag: reads a chip without keys, as a customer's phone would, and asks the server whether
 * its SUN message verifies. [tagId] is the tag page the admin came from, or null from anywhere else.
 */
@Composable
fun VerifyTagScreen(
    tagId: String?,
    readUrl: (IsoDep) -> String,
    verify: (url: String, tagId: String?) -> VerifyResponse,
    onClose: () -> Unit,
) {
    val vm: VerifyTagViewModel = viewModel(
        factory = viewModelFactory { initializer { VerifyTagViewModel(tagId, readUrl, verify) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // No keys on this screen, so no FLAG_SECURE; the screen is kept on while it shows.
    NfcReaderScreen(
        title = "Verify tag",
        purpose = "verify tags",
        secure = false,
        onTag = vm::onTagDiscovered,
        onClose = onClose,
    ) {
        when (state.phase) {
            VerifyPhase.WAITING ->
                Text("Hold the tag to the back of the phone.", style = MaterialTheme.typography.titleLarge)
            VerifyPhase.READING -> Busy("Reading the chip…")
            VerifyPhase.CHECKING -> Busy("Checking with the server…")
            VerifyPhase.FAILED -> ErrorCard(state.error.orEmpty(), state.url)
            VerifyPhase.DONE -> state.result?.let { r ->
                ResultCard(r, state.verdict ?: verdictFor(r), state.url)
            }
        }

        if (state.phase == VerifyPhase.DONE || state.phase == VerifyPhase.FAILED) {
            Text(
                "Hold another tag to the phone to check it.",
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
private fun ErrorCard(message: String, url: String?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Not verified", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(message)
            url?.let { UrlLine(it) }
        }
    }
}

@Composable
private fun ResultCard(r: VerifyResponse, verdict: Verdict, url: String?) {
    val colors = if (verdict.verified) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (verdict.verified) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(verdict.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            verdict.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val w = 128.dp
            Field("Tag", r.tag.code, labelWidth = w)
            Field("Shop", r.tag.shopName ?: "—", labelWidth = w)
            r.tag.label?.takeIf { it.isNotBlank() }?.let { Field("Label", it, labelWidth = w) }
            Field("Written on", formatDate(r.tag.encodedAt), labelWidth = w)
            Field("UID", r.uid, mono = true, labelWidth = w)
            Field("Chip counter", r.counter.toString(), labelWidth = w)
            Field("Server counter", r.lastCounter?.toString() ?: "none", labelWidth = w)
            url?.let { UrlLine(it) }
        }
    }
}

@Composable
private fun UrlLine(url: String) {
    Text(
        url,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)

/** ISO 8601 from the server as a local date and time; the raw text if it doesn't parse. */
internal fun formatDate(iso: String?): String {
    if (iso.isNullOrBlank()) return "Not recorded"
    val instant = runCatching { Instant.parse(iso) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
        ?: return iso
    return DATE.format(instant.atZone(ZoneId.systemDefault()))
}
