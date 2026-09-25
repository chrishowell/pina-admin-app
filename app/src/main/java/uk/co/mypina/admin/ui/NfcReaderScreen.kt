package uk.co.mypina.admin.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * The frame shared by the native NFC screens (Write, Verify): a top bar with Back, back button =
 * [onClose], the screen kept on while showing (plus FLAG_SECURE when [secure], for screens that
 * handle keys), NFC reader mode only while resumed (so Android doesn't open the tag's URL itself;
 * guide §5), a notice when the phone has no NFC or it is off, and a scrolling column for [content].
 *
 * [actions] go at the end of the top bar (e.g. an overflow menu).
 * [onTag] is called on the NFC binder thread for each tag that comes into the field.
 * [purpose] finishes "This phone has no NFC, so it can't …" and "NFC is off. Turn it on to …".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcReaderScreen(
    title: String,
    purpose: String,
    secure: Boolean,
    onTag: (Tag) -> Unit,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val activity = LocalContext.current.findActivity()
    val currentOnTag by rememberUpdatedState(onTag)

    BackHandler(onBack = onClose)

    DisposableEffect(activity, secure) {
        val flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            (if (secure) WindowManager.LayoutParams.FLAG_SECURE else 0)
        activity.window.addFlags(flags)
        onDispose { activity.window.clearFlags(flags) }
    }

    val adapter = remember(activity) { NfcAdapter.getDefaultAdapter(activity) }
    var nfcOn by remember { mutableStateOf(adapter?.isEnabled == true) }
    LifecycleResumeEffect(adapter) {
        nfcOn = adapter?.isEnabled == true
        adapter?.enableReaderMode(
            activity,
            { tag -> currentOnTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
        onPauseOrDispose { adapter?.disableReaderMode(activity) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                adapter == null -> Notice("This phone has no NFC, so it can't $purpose.")
                !nfcOn -> {
                    Notice("NFC is off. Turn it on to $purpose.")
                    OutlinedButton(onClick = { activity.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) {
                        Text("Open NFC settings")
                    }
                }
            }
            content()
        }
    }
}

/** A label/value row on a result card. */
@Composable
fun Field(label: String, value: String, mono: Boolean = false, labelWidth: Dp = 80.dp) {
    Row {
        Text(
            label,
            modifier = Modifier.width(labelWidth),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
}

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("NFC screens need an Activity")
}
