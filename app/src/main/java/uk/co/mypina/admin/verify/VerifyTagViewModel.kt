package uk.co.mypina.admin.verify

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
import uk.co.mypina.admin.api.VerifyResponse
import uk.co.mypina.admin.nfc.IsoDepConnection
import uk.co.mypina.admin.nfc.TagReadException
import uk.co.mypina.admin.nfc.connectIsoDep
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

enum class VerifyPhase { WAITING, READING, CHECKING, DONE, FAILED }

data class VerifyState(
    val phase: VerifyPhase = VerifyPhase.WAITING,
    /** The URL read from the chip (with its real e= and c=), once read. */
    val url: String? = null,
    val result: VerifyResponse? = null,
    val verdict: Verdict? = null,
    val error: String? = null,
)

/**
 * One tap = one keyless read of the chip ([readUrl]: select + a single ReadData) and one POST to
 * the verify endpoint ([verify]). Reader mode stays on, so after a result (or a failure) the next
 * tag held to the phone starts over: that's "Scan another".
 */
class VerifyTagViewModel(
    private val tagId: String?,
    private val readUrl: (IsoDep) -> String,
    private val verify: (url: String, tagId: String?) -> VerifyResponse,
) : ViewModel() {

    private val _state = MutableStateFlow(VerifyState())
    val state: StateFlow<VerifyState> = _state.asStateFlow()

    private val busy = AtomicBoolean(false)

    @Volatile private var activeIso: IsoDep? = null

    /** Clears the last result; the screen goes back to "Hold the tag…". Ignored mid-read. */
    fun reset() {
        if (!busy.get()) _state.value = VerifyState()
    }

    /** Called from the NFC reader-mode callback (a binder thread). */
    fun onTagDiscovered(tag: Tag) {
        if (!busy.compareAndSet(false, true)) return

        val iso = when (val c = connectIsoDep(tag)) {
            is IsoDepConnection.Connected -> c.iso
            IsoDepConnection.NotIsoDep -> {
                _state.value = failed(null, "This isn't an NTAG 424 DNA chip (no ISO-DEP).")
                busy.set(false)
                return
            }
            IsoDepConnection.Failed -> {
                _state.value = failed(null, "Couldn't connect to the chip. Hold it again.")
                busy.set(false)
                return
            }
        }

        _state.value = VerifyState(phase = VerifyPhase.READING)
        activeIso = iso

        viewModelScope.launch(Dispatchers.IO) {
            var url: String? = null
            try {
                ensureActive()
                url = try {
                    readUrl(iso)
                } finally {
                    // The chip is done with after one read; the server call doesn't need it.
                    activeIso = null
                    runCatching { iso.close() }
                }
                _state.update { it.copy(phase = VerifyPhase.CHECKING, url = url) }
                val response = verify(url, tagId)
                _state.update {
                    it.copy(phase = VerifyPhase.DONE, result = response, verdict = verdictFor(response))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Verify failed: ${e.javaClass.simpleName}")
                _state.value = failed(url, verifyErrorMessage(e, url))
            } finally {
                activeIso = null
                runCatching { iso.close() }
                busy.set(false)
            }
        }
    }

    override fun onCleared() {
        activeIso?.let { runCatching { it.close() } }
    }

    private fun failed(url: String?, message: String) =
        VerifyState(phase = VerifyPhase.FAILED, url = url, error = message)

    private companion object {
        const val TAG = "VerifyTag"
    }
}

/** What the Verify screen says for a failure. [url] is the chip's URL if it was read. */
internal fun verifyErrorMessage(e: Throwable, url: String?): String = when (e) {
    is TagReadException -> e.message.orEmpty()
    is ApiException.BadCmac -> "The signature didn't verify: this chip wasn't written with Piña's keys."
    is ApiException.UnknownTag ->
        "No tag has the code in this chip's URL (${url?.let(::tagCodeFromUrl) ?: url ?: "no code"})."
    is ApiException.Unsigned -> "This is a demo tag (unsigned): it has no signature to verify."
    is ApiException.Malformed -> "The server couldn't read this chip's URL (malformed). It may not be a Piña tag."
    is ApiException -> e.message.orEmpty()
    is TagLostException -> "The chip moved away from the phone. Hold it again."
    is IOException -> "Lost contact with the chip. Hold it again."
    else -> "Something went wrong (${e.javaClass.simpleName})."
}
