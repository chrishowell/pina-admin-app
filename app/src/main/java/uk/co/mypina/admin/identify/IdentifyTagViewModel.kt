package uk.co.mypina.admin.identify

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.mypina.admin.api.ApiException
import uk.co.mypina.admin.api.IdentifyResponse
import uk.co.mypina.admin.nfc.IsoDepConnection
import uk.co.mypina.admin.nfc.connectIsoDep
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

enum class IdentifyPhase { WAITING, READING, CHECKING, DONE, FAILED }

data class IdentifyState(
    val phase: IdentifyPhase = IdentifyPhase.WAITING,
    val uid: String? = null,
    /** The URL read from the chip, if it had one. */
    val url: String? = null,
    val result: IdentifyResponse? = null,
    val verdict: IdentifyVerdict? = null,
    val error: String? = null,
)

/**
 * One tap = the UID from the ISO-DEP tag id, one keyless read of the chip ([readChip]: select + a
 * single ReadData, "no URL" outcomes folded into [ChipRead]) and one POST to the identify endpoint
 * ([identify]). Reader mode stays on, so the next tag held to the phone starts over.
 */
class IdentifyTagViewModel(
    private val readChip: (IsoDep) -> ChipRead,
    private val identify: (uid: String, url: String?) -> IdentifyResponse,
) : ViewModel() {

    private val _state = MutableStateFlow(IdentifyState())
    val state: StateFlow<IdentifyState> = _state.asStateFlow()

    private val busy = AtomicBoolean(false)

    @Volatile private var activeIso: IsoDep? = null

    /** Clears the last result; the screen goes back to "Hold the tag…". Ignored mid-read. */
    fun reset() {
        if (!busy.get()) _state.value = IdentifyState()
    }

    /** Called from the NFC reader-mode callback (a binder thread). */
    fun onTagDiscovered(tag: Tag) {
        if (!busy.compareAndSet(false, true)) return

        val uid = uidFromTagId(tag.id)
        if (uid == null) {
            _state.value = failed(null, null, RANDOM_ID_MESSAGE)
            busy.set(false)
            return
        }

        val iso: IsoDep? = when (val c = connectIsoDep(tag)) {
            is IsoDepConnection.Connected -> c.iso
            // No ISO-DEP: still identified by its UID.
            IsoDepConnection.NotIsoDep -> null
            IsoDepConnection.Failed -> {
                _state.value = failed(uid, null, "Couldn't connect to the chip. Hold it again.")
                busy.set(false)
                return
            }
        }

        _state.value = IdentifyState(phase = IdentifyPhase.READING, uid = uid)
        activeIso = iso

        viewModelScope.launch(Dispatchers.IO) {
            var url: String? = null
            try {
                ensureActive()
                val read = if (iso == null) {
                    ChipRead.NotNtag424("no ISO-DEP")
                } else {
                    try {
                        readChip(iso)
                    } finally {
                        // The chip is done with after one read; the server call doesn't need it.
                        activeIso = null
                        runCatching { iso.close() }
                    }
                }
                url = read.url
                _state.update { it.copy(phase = IdentifyPhase.CHECKING, url = url) }
                val response = identify(uid, url)
                _state.update {
                    it.copy(phase = IdentifyPhase.DONE, result = response, verdict = identifyVerdict(response, read))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Identify failed: ${e.javaClass.simpleName}")
                _state.value = failed(uid, url, identifyErrorMessage(e))
            } finally {
                activeIso = null
                iso?.let { runCatching { it.close() } }
                busy.set(false)
            }
        }
    }

    override fun onCleared() {
        activeIso?.let { runCatching { it.close() } }
    }

    private fun failed(uid: String?, url: String?, message: String) =
        IdentifyState(phase = IdentifyPhase.FAILED, uid = uid, url = url, error = message)

    private companion object {
        const val TAG = "IdentifyTag"
    }
}

/** What the Identify screen says for a failure. */
internal fun identifyErrorMessage(e: Throwable): String = when (e) {
    is ApiException.Malformed -> "The server refused this chip's UID (malformed)."
    is ApiException -> e.message.orEmpty()
    is TagLostException -> "The chip moved away from the phone. Hold it again."
    is IOException -> "Lost contact with the chip. Hold it again."
    else -> "Something went wrong (${e.javaClass.simpleName})."
}
