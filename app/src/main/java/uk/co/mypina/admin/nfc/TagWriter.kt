package uk.co.mypina.admin.nfc

import android.nfc.tech.IsoDep
import uk.co.mypina.admin.api.AdminApi

/** The steps shown on the Write screen, in order. */
enum class Step(val label: String) {
    READ_UID("Read UID"),
    FETCHED("Fetched from server"),
    AUTHENTICATED("Authenticated"),
    URL_WRITTEN("URL written"),
    SDM_SET("SDM set"),
    KEY1("Key 1 changed"),
    KEY2("Key 2 changed"),
    KEY0("Key 0 changed"),
    VERIFIED("Verified"),
}

enum class StepState { PENDING, RUNNING, DONE, FAILED }

/** What the result card shows. No keys in here. */
data class WriteResult(
    val tagCode: String,
    val shopName: String,
    val uid: String,
    val counter: Long,
)

/**
 * Thrown by a [TagWriter] to say which step failed. The message is shown to the person, so it
 * must never contain key material or raw APDU data.
 */
class TagWriteException(val step: Step, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Personalises one NTAG 424 DNA chip for [tagId]: read UID, fetch the job from the server, drive
 * the chip (guide §3), then report the read-back URL to the server.
 *
 * Contract:
 * - Called on a background thread (Dispatchers.IO) with [iso] already connected and its timeout
 *   set to 5000 ms. The caller closes [iso] afterwards; implementations must not.
 * - Call [onStep] with RUNNING when a step starts and DONE when it ends. Steps may be reported
 *   DONE without RUNNING when state detection skips them (e.g. a key already at version 01).
 * - On failure throw: [TagWriteException] (names the step), an ApiException from [AdminApi]
 *   (message is user-facing), or an IOException/TagLostException from the chip. The caller marks
 *   the RUNNING step failed and tells the person to hold the tag again.
 * - Keys come only from the server's response; never generate, log or persist them. Drop the
 *   response and zero any key byte arrays before returning.
 */
interface TagWriter {
    fun write(iso: IsoDep, tagId: String, onStep: (Step, StepState) -> Unit): WriteResult
}

/** The single place that decides which writer the app uses. */
object TagWriters {
    fun create(api: AdminApi): TagWriter = Ntag424TagWriter(api)
}
