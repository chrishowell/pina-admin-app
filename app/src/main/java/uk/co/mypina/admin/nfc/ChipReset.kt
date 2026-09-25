package uk.co.mypina.admin.nfc

import net.bplearning.ntag424.command.ChangeKey
import uk.co.mypina.admin.api.ApiException
import uk.co.mypina.admin.api.PersonaliseResponse

/** The steps shown while resetting a chip, in order (a subset of [Step]). */
val RESET_STEPS = listOf(Step.READ_UID, Step.FETCHED, Step.AUTHENTICATED, Step.KEY1, Step.KEY2, Step.KEY0, Step.VERIFIED)

/** What the reset result card shows. No keys in here. */
data class ResetResult(val uid: String)

/**
 * Puts keys 0, 1 and 2 of a chip this server wrote back to factory (16 zero bytes, version 0), so
 * another server (dev ↔ production) can write it. Same shape as [ChipSequence]: no Android types,
 * a transceiver lambda, and [personalise] for this server's derived keys (it changes nothing on
 * the server). The NDEF file and its SDM settings are left alone and [ChipSequence]'s
 * `personalised` is never called: the tag row on the server is not changed.
 *
 *  1. READ_UID       UID from the ISO-DEP tag id (Random ID is refused: Piña never enables it),
 *                    ISOSelectFile by DF name, GetKeyVersion 0/1/2 (plain, unauthenticated).
 *  2. FETCHED        [personalise]: this server's derived key 0/1/2 at keyVersion (the target).
 *  3. AUTHENTICATED  Key 0 at version 0: the factory key only. At the target version: this
 *                    server's derived key 0 only (a failure means another server wrote it).
 *  4. KEY1, KEY2     ChangeKey (case 1, old key = derived key, new = zeros, version 0) when the
 *                    slot is at the target version; skipped (DONE) at version 0.
 *  5. KEY0           ChangeKey (case 2) to zeros version 0, last (it ends the session); skipped
 *                    when key 0 was already factory.
 *  6. VERIFIED       Re-select, GetKeyVersion 0/1/2 must all read 0.
 *
 * Key byte arrays are zeroed in `finally`.
 */
class ChipReset(
    transceive: (ByteArray) -> ByteArray,
    private val personalise: (uidHex: String) -> PersonaliseResponse,
) {
    private val comm = ChipCommands.communicator(transceive)

    /** Which step to blame if something throws. */
    private var current = Step.READ_UID

    fun run(tagIdBytes: ByteArray?, onStep: (Step, StepState) -> Unit): ResetResult {
        val secrets = mutableListOf<ByteArray>()
        fun secret(hex: String): ByteArray = hex.hexToBytes().also {
            secrets += it
            if (it.size != 16) throw TagWriteException(Step.FETCHED, "The server sent a key of the wrong length.")
        }
        fun zeros(): ByteArray = ByteArray(16).also { secrets += it }
        fun start(s: Step) { current = s; onStep(s, StepState.RUNNING) }
        fun done(s: Step) = onStep(s, StepState.DONE)

        try {
            // 1. READ_UID, and the chip's key versions (before anything is fetched or tried).
            start(Step.READ_UID)
            val uid = tagIdBytes?.takeIf { it.size == 7 && it[0] != 0x08.toByte() }?.toHex()
                ?: throw TagWriteException(
                    Step.READ_UID,
                    "This chip hides its UID (Random ID). Piña never writes chips like that, so there are no Piña keys to reset.",
                )
            ChipCommands.selectNdefApplication(comm, current)
            val versions = ChipCommands.readKeyVersionsOrNull(comm)
                ?: throw TagWriteException(Step.READ_UID, "The chip wouldn't report its key versions, so it can't be reset safely.")
            done(Step.READ_UID)

            // 2. FETCHED
            start(Step.FETCHED)
            val job = personalise(uid)
            val target = job.keys.keyVersion
            if (target !in 1..255) throw TagWriteException(Step.FETCHED, "The server uses key version $target, which can't be told apart from factory keys.")
            val derived = listOf(secret(job.keys.key0), secret(job.keys.key1), secret(job.keys.key2))
            done(Step.FETCHED)

            // 3. AUTHENTICATED: exactly one key 0 is tried, chosen by its version.
            start(Step.AUTHENTICATED)
            when (versions[0]) {
                0 -> ChipCommands.authenticateKey0(comm, listOf(zeros()))
                    ?: throw TagWriteException(Step.AUTHENTICATED, "This chip's key 0 says it is factory, but the factory key was refused; it can't be reset.")
                target -> ChipCommands.authenticateKey0(comm, listOf(derived[0]))
                    ?: throw TagWriteException(Step.AUTHENTICATED, "This chip was written by a different server; reset it from that server.")
                else -> throw TagWriteException(
                    Step.AUTHENTICATED,
                    "This chip's key 0 is at version ${versions[0]}, which neither the factory nor Piña set; it can't be reset.",
                )
            }
            done(Step.AUTHENTICATED)

            // 4. KEY1, KEY2: old key = this server's derived key.
            for ((slot, step) in listOf(1 to Step.KEY1, 2 to Step.KEY2)) {
                current = step
                when (planKeyReset(slot, versions[slot], target)) {
                    KeyAction.SKIP -> done(step)
                    KeyAction.CHANGE -> {
                        start(step)
                        ChangeKey.run(comm, slot, derived[slot], zeros(), 0)
                        done(step)
                    }
                }
            }

            // 5. KEY0, last: it ends the session.
            current = Step.KEY0
            when (planKeyReset(0, versions[0], target)) {
                KeyAction.SKIP -> done(Step.KEY0)
                KeyAction.CHANGE -> {
                    start(Step.KEY0)
                    ChipCommands.changeKey0(comm, zeros(), 0)
                    done(Step.KEY0)
                }
            }

            // 6. VERIFIED
            start(Step.VERIFIED)
            ChipCommands.selectNdefApplication(comm, current)
            val after = ChipCommands.readKeyVersions(comm)
            after.forEachIndexed { slot, v ->
                if (v != 0) throw TagWriteException(Step.VERIFIED, "Key $slot still reads version $v after the reset. Hold the tag again.")
            }
            done(Step.VERIFIED)
            return ResetResult(uid)
        } catch (e: TagWriteException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ChipCommands.wrap(comm, current, e)
        } finally {
            secrets.forEach { it.fill(0) }
        }
    }
}

/**
 * State detection for one slot when resetting: version 0 is factory already → skip; the target
 * version is this server's key → change back to zeros; anything else is a key whose old value we
 * don't know, so ChangeKey (which XORs with the old key) can't be sent → fail.
 */
fun planKeyReset(slot: Int, currentVersion: Int, targetVersion: Int): KeyAction {
    require(slot in 0..2)
    return when (currentVersion) {
        0 -> KeyAction.SKIP
        targetVersion -> KeyAction.CHANGE
        else -> throw TagWriteException(
            when (slot) { 0 -> Step.KEY0; 1 -> Step.KEY1; else -> Step.KEY2 },
            "Key $slot is at version $currentVersion, which neither the factory nor Piña set; it can't be reset.",
        )
    }
}
