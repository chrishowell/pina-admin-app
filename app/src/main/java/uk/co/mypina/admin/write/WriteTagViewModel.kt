package uk.co.mypina.admin.write

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.mypina.admin.api.ApiException
import uk.co.mypina.admin.nfc.IsoDepConnection
import uk.co.mypina.admin.nfc.Step
import uk.co.mypina.admin.nfc.StepState
import uk.co.mypina.admin.nfc.TagWriteException
import uk.co.mypina.admin.nfc.TagWriter
import uk.co.mypina.admin.nfc.WriteResult
import uk.co.mypina.admin.nfc.connectIsoDep
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

enum class Phase { WAITING, WRITING, FAILED, DONE }

data class WriteState(
    val phase: Phase = Phase.WAITING,
    val steps: Map<Step, StepState> = Step.entries.associateWith { StepState.PENDING },
    val failedStep: Step? = null,
    val error: String? = null,
    /** False when holding the tag again won't help (not signed in, UID taken, ...). */
    val retryable: Boolean = true,
    val result: WriteResult? = null,
)

class WriteTagViewModel(
    private val tagId: String,
    private val writer: TagWriter,
) : ViewModel() {

    private val _state = MutableStateFlow(WriteState())
    val state: StateFlow<WriteState> = _state.asStateFlow()

    private val busy = AtomicBoolean(false)

    /** The chip being written, so [onCleared] can close it and abort a write in flight. */
    @Volatile private var activeIso: IsoDep? = null

    /** Called from the NFC reader-mode callback (a binder thread). */
    fun onTagDiscovered(tag: Tag) {
        if (_state.value.phase == Phase.DONE) return
        if (!busy.compareAndSet(false, true)) return

        val iso = when (val c = connectIsoDep(tag)) {
            is IsoDepConnection.Connected -> c.iso
            IsoDepConnection.NotIsoDep -> {
                fail(Step.READ_UID, "This isn't an NTAG 424 DNA chip (no ISO-DEP).", retryable = true)
                busy.set(false)
                return
            }
            IsoDepConnection.Failed -> {
                fail(Step.READ_UID, "Couldn't connect to the chip.", retryable = true)
                busy.set(false)
                return
            }
        }

        // Every attempt redoes the whole sequence; the writer's state detection skips what's done.
        _state.value = WriteState(phase = Phase.WRITING)
        activeIso = iso

        viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureActive() // the screen may have gone between the tap and here
                val result = writer.write(iso, tagId) { step, s ->
                    _state.update { it.copy(steps = it.steps + (step to s)) }
                }
                _state.update { it.copy(phase = Phase.DONE, result = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val step = (e as? TagWriteException)?.step
                    ?: _state.value.steps.entries.firstOrNull { it.value == StepState.RUNNING }?.key
                    ?: _state.value.steps.entries.firstOrNull { it.value == StepState.PENDING }?.key
                    ?: Step.VERIFIED
                // Class name only: messages from the chip library could carry APDU bytes.
                Log.w(TAG, "Write failed at $step: ${e.javaClass.simpleName}")
                fail(step, messageFor(e), retryable = isRetryable(e))
            } finally {
                activeIso = null
                runCatching { iso.close() }
                busy.set(false)
            }
        }
    }

    /**
     * The Write screen has gone. Cancelling the coroutine can't interrupt the blocking chip I/O, so
     * close the IsoDep too: any transceive in progress or to come then fails and the write stops.
     * (Stopping mid-sequence is safe: the state detection resumes it on the next attempt.)
     */
    override fun onCleared() {
        activeIso?.let { runCatching { it.close() } }
    }

    private fun fail(step: Step, message: String, retryable: Boolean) {
        _state.update {
            it.copy(
                phase = Phase.FAILED,
                steps = it.steps + (step to StepState.FAILED),
                failedStep = step,
                error = message,
                retryable = retryable,
            )
        }
    }

    /**
     * False when holding the tag again won't help: not signed in, the shop/tag isn't in a
     * writable state, or the server is misconfigured. True for transport hiccups and the errors
     * that name a race or an integrity check the chip may pass on a fresh attempt.
     */
    private fun isRetryable(e: Throwable): Boolean = when (e) {
        !is ApiException -> true
        is ApiException.Network, is ApiException.Http -> true
        is ApiException.Replayed, is ApiException.BadCmac, is ApiException.UidMismatch -> true
        else -> false
    }

    private fun messageFor(e: Throwable): String = when (e) {
        is TagWriteException -> e.message.orEmpty()
        is ApiException -> e.message.orEmpty()
        is TagLostException -> "The chip moved away from the phone."
        is IOException -> "Lost contact with the chip."
        else -> "Something went wrong (${e.javaClass.simpleName})."
    }

    private companion object {
        const val TAG = "WriteTag"
    }
}
