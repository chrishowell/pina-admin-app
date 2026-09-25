package uk.co.mypina.admin.nfc

import net.bplearning.ntag424.CommandResult
import net.bplearning.ntag424.CommunicationMode
import net.bplearning.ntag424.DnaCommunicator
import net.bplearning.ntag424.command.ChangeKey
import net.bplearning.ntag424.command.GetCardUid
import net.bplearning.ntag424.command.GetKeyVersion
import net.bplearning.ntag424.command.ReadData
import net.bplearning.ntag424.constants.Ntag424
import net.bplearning.ntag424.encryptionmode.AESEncryptionMode
import net.bplearning.ntag424.exception.DelayException
import net.bplearning.ntag424.exception.MACValidationException
import net.bplearning.ntag424.exception.ProtocolException
import uk.co.mypina.admin.api.ApiException
import uk.co.mypina.admin.api.PersonaliseResponse
import uk.co.mypina.admin.api.PersonalisedResponse
import java.io.IOException

/**
 * The NTAG 424 DNA personalisation sequence (guide §3), free of Android types so it can be unit
 * tested against a fake transceiver replaying AN12196 rev 2.0.
 *
 *  1. READ_UID       UID from the ISO-DEP tag id (7 bytes, not starting 08). Otherwise (Random ID)
 *                    it is read with GetCardUid after authenticating with the factory key 0.
 *  2. FETCHED        [personalise] (the server's job: NDEF bytes, SDM settings, keys).
 *  3. AUTHENTICATED  ISOSelectFile by DF name, GetKeyVersion 0/1/2 (plain, unauthenticated),
 *                    AuthenticateEV2First with key 0 trying key0Candidates, ordered by
 *                    [orderKey0Candidates]: Piña's keys first when key 0 is at the target version,
 *                    the factory key first when it is at version 0 (so a rewrite doesn't add a
 *                    failed authentication to the chip's counters every time).
 *  4. URL_WRITTEN    GetFileSettings file 02 (MACed, in the session), then WriteData file 02 at
 *                    offset 0 in the file's own CommMode (plain / MAC / full), still inside the
 *                    session. Always redone (idempotent).
 *  5. SDM_SET        ChangeFileSettings file 02. Always redone (idempotent).
 *  6. KEY1, KEY2     ChangeKey (case 1, old key = factory zeros) when the slot is at version 0;
 *                    skipped (reported DONE) when already at the target version.
 *  7. KEY0           ChangeKey (case 2) last; skipped when key 0 is already Piña's.
 *  8. VERIFIED       Re-select (drops the session), ReadData file 02 plain, parse the URL,
 *                    [personalised].
 *
 * WriteData, ChangeFileSettings and ChangeKey 0 go through [ChipCommands] rather than the
 * library's command classes; see there for why.
 *
 * Keys are never logged; the communicator's logger is left at its default no-op. Key byte arrays
 * are zeroed in `finally`.
 */
class ChipSequence(
    transceive: (ByteArray) -> ByteArray,
    private val personalise: (uidHex: String) -> PersonaliseResponse,
    private val personalised: (uidHex: String, url: String) -> PersonalisedResponse,
) {
    // NB: inside apply{}, a bare `transceive` would resolve to DnaCommunicator.transceive.
    private val chip: (ByteArray) -> ByteArray = transceive
    private val comm = DnaCommunicator().apply {
        setTransceiver { apdu ->
            val r = chip(apdu)
            // CommandResult needs at least the two status bytes.
            if (r.size < 2) throw IOException("Short response from the chip")
            r
        }
    }

    /** Which step to blame if something throws. */
    private var current = Step.READ_UID

    fun run(tagIdBytes: ByteArray?, onStep: (Step, StepState) -> Unit): WriteResult {
        val secrets = mutableListOf<ByteArray>()
        fun secret(hex: String): ByteArray = hex.hexToBytes().also {
            secrets += it
            if (it.size != 16) throw TagWriteException(Step.FETCHED, "The server sent a key of the wrong length.")
        }
        fun start(s: Step) { current = s; onStep(s, StepState.RUNNING) }
        fun done(s: Step) = onStep(s, StepState.DONE)

        try {
            // 1. READ_UID
            start(Step.READ_UID)
            var uid: String? = tagIdBytes?.takeIf { it.size == 7 && it[0] != 0x08.toByte() }?.toHex()
            var job: PersonaliseResponse? = null
            var authKey: ByteArray? = null
            var versions: IntArray? = null

            if (uid != null) {
                done(Step.READ_UID)
                start(Step.FETCHED)
                job = personalise(uid)
                done(Step.FETCHED)
                start(Step.AUTHENTICATED)
                selectNdefApplication()
                versions = readKeyVersionsOrNull()
                val candidates = orderKey0Candidates(job.key0Candidates.map(::secret), versions?.get(0), job.keys.keyVersion)
                authKey = authenticate(candidates)
                    ?: throw TagWriteException(Step.AUTHENTICATED, "This chip's key 0 is not factory or Piña's; it cannot be written.")
            } else {
                // Random ID: the tag id isn't the UID. Only a factory chip can be read this way,
                // since Piña never enables Random ID and derived keys need the UID first.
                selectNdefApplication()
                versions = readKeyVersionsOrNull()
                val factory = ByteArray(16).also { secrets += it }
                authKey = authenticate(listOf(factory))
                    ?: throw TagWriteException(
                        Step.READ_UID,
                        "This chip hides its UID (Random ID) and its key 0 isn't the factory key; it cannot be written.",
                    )
                uid = GetCardUid.run(comm).toHex()
                done(Step.READ_UID)
                start(Step.FETCHED)
                job = personalise(uid)
                done(Step.FETCHED)
                start(Step.AUTHENTICATED)
            }
            if (versions == null) versions = readKeyVersions() // now in MAC mode, authenticated
            done(Step.AUTHENTICATED)

            val target = job.keys.keyVersion
            if (target !in 1..255) throw TagWriteException(Step.FETCHED, "The server asked for key version $target, which can't be told apart from factory keys.")
            val key0 = secret(job.keys.key0)
            val key1 = secret(job.keys.key1)
            val key2 = secret(job.keys.key2)
            val ndef = job.ndefFileHex.hexToBytes()

            // 4. URL_WRITTEN
            start(Step.URL_WRITTEN)
            if (!ChipCommands.fitsOneWrite(ndef.size)) {
                throw TagWriteException(Step.URL_WRITTEN, "The NDEF data from the server is ${ndef.size} bytes; it must be 1..239.")
            }
            // The datasheet: WriteData uses the CommMode from the file's settings. A factory file 02
            // is plain (free write), and after our ChangeFileSettings it is plain with write key 0,
            // so on real chips this is normally the plain path, still inside the key 0 session.
            val mode = ChipCommands.fileCommMode(comm, Ntag424.NDEF_FILE_NUMBER)
            ChipCommands.writeData(comm, mode, Ntag424.NDEF_FILE_NUMBER, 0, ndef)
            done(Step.URL_WRITTEN)

            // 5. SDM_SET
            start(Step.SDM_SET)
            val settings = try {
                SdmFileSettings.dataField(job.sdm)
            } catch (e: IllegalArgumentException) {
                throw TagWriteException(Step.SDM_SET, e.message ?: "Unsupported SDM settings from the server.", e)
            }
            ChipCommands.changeFileSettings(comm, Ntag424.NDEF_FILE_NUMBER, settings)
            done(Step.SDM_SET)

            // 6. KEY1, KEY2 (old key is the factory key when the slot is at version 0)
            for ((slot, step, newKey) in listOf(Triple(1, Step.KEY1, key1), Triple(2, Step.KEY2, key2))) {
                current = step
                when (planKeyChange(slot, versions[slot], target, authKeyIsTarget = false)) {
                    KeyAction.SKIP -> done(step)
                    KeyAction.CHANGE -> {
                        start(step)
                        val factory = ByteArray(16).also { secrets += it }
                        ChangeKey.run(comm, slot, factory, newKey, target)
                        done(step)
                    }
                }
            }

            // 7. KEY0, last: it ends the session.
            current = Step.KEY0
            when (planKeyChange(0, versions[0], target, authKeyIsTarget = authKey.contentEquals(key0))) {
                KeyAction.SKIP -> done(Step.KEY0)
                KeyAction.CHANGE -> {
                    start(Step.KEY0)
                    ChipCommands.changeKey0(comm, key0, target)
                    done(Step.KEY0)
                }
            }

            // 8. VERIFIED: re-select drops authentication, so the read gets SDM mirroring.
            start(Step.VERIFIED)
            selectNdefApplication()
            val file = readPlain(Ntag424.NDEF_FILE_NUMBER, ndef.size)
            val url = try {
                NdefUri.parseFile(file)
            } catch (e: IllegalArgumentException) {
                throw TagWriteException(Step.VERIFIED, "The chip's read-back isn't a URL record (${e.message}).", e)
            }
            if (url.substringBefore('?') != job.url.substringBefore('?') || url.length != job.url.length) {
                throw TagWriteException(Step.VERIFIED, "The chip's read-back doesn't match the URL that was written.")
            }
            val tag = job.tag
            job = null // drop the keys before the last network call
            val ok = personalised(uid, url)
            done(Step.VERIFIED)
            return WriteResult(tagCode = tag.code, shopName = tag.shopName, uid = uid, counter = ok.counter)
        } catch (e: TagWriteException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw wrap(current, e)
        } finally {
            secrets.forEach { it.fill(0) }
        }
    }

    // ---- chip commands -------------------------------------------------------------------------

    /** ISOSelectFile by DF name (AN12196 Table 22): `00 A4 04 0C 07 D2760000850101 00` → 90 00. */
    private fun selectNdefApplication() {
        val r = comm.transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x0C, Ntag424.DF_NAME.size.toByte(), *Ntag424.DF_NAME, 0x00))
        if (r.size < 2 || r[r.size - 2] != 0x90.toByte() || r[r.size - 1] != 0x00.toByte()) {
            throw TagWriteException(current, "Couldn't select the chip's NDEF application (status ${statusOf(r)}). Is this an NTAG 424 DNA?")
        }
        // Selecting ends any session on the chip; keep the library's view in step.
        comm.startEncryptedSession(null, 0, 0, null)
    }

    /** GetKeyVersion for keys 0..2 without authentication, or null if the chip refuses. */
    private fun readKeyVersionsOrNull(): IntArray? = try {
        readKeyVersions()
    } catch (e: ProtocolException) {
        null
    }

    private fun readKeyVersions(): IntArray = IntArray(3) { GetKeyVersion.run(comm, it) and 0xFF }

    /** Tries each key 0 in order; returns the one that worked, or null if none did. */
    private fun authenticate(candidates: List<ByteArray>): ByteArray? {
        for (key in candidates) {
            try {
                if (AESEncryptionMode.authenticateEV2(comm, 0, key)) return key
            } catch (e: DelayException) {
                throw TagWriteException(
                    Step.AUTHENTICATED,
                    "The chip is delaying authentication after failed attempts. Wait a minute, then hold it again.",
                    e,
                )
            }
            val last = comm.lastCommandResult
            val wrongKey = last != null && last.status1 == 0x91.toByte() &&
                (last.status2 == CommandResult.AUTHENTICATION_ERROR || last.status2 == CommandResult.SUCCESS)
            if (!wrongKey) {
                throw TagWriteException(Step.AUTHENTICATED, "The chip refused authentication (status ${statusOf(last)}).")
            }
        }
        return null
    }

    /** ReadData in plain mode, following 91 AF continuation frames. */
    private fun readPlain(fileNo: Int, length: Int): ByteArray {
        var out = ReadData.run(comm, CommunicationMode.PLAIN, fileNo, 0, length)
        var guard = 0
        while (comm.lastCommandResult.status2 == CommandResult.ADDITIONAL_FRAME_EXPECTED && guard++ < 8) {
            val more = comm.nxpNativeCommand(0xAF.toByte(), null, null, null)
            more.throwUnlessSuccessful()
            out += more.data
        }
        if (out.size < length) throw TagWriteException(Step.VERIFIED, "The chip returned ${out.size} of $length bytes.")
        return out
    }

    // ---- errors --------------------------------------------------------------------------------

    private fun wrap(step: Step, e: Exception): TagWriteException = when (e) {
        is DelayException -> TagWriteException(step, "The chip is delaying authentication after failed attempts. Wait a minute, then hold it again.", e)
        is MACValidationException -> TagWriteException(step, "The chip's reply to \"${step.label}\" failed its integrity check. Hold it again.", e)
        is ProtocolException -> TagWriteException(step, "The chip refused \"${step.label}\" (status ${statusOf(comm.lastCommandResult)}). Hold it again to continue.", e)
        is IOException -> TagWriteException(step, "Lost contact with the chip at \"${step.label}\". Hold it again to continue.", e)
        is IllegalArgumentException -> TagWriteException(step, "Unexpected data at \"${step.label}\".", e)
        else -> TagWriteException(step, "Something went wrong at \"${step.label}\" (${e.javaClass.simpleName}).", e)
    }

    companion object {
        private fun statusOf(r: CommandResult?): String =
            if (r == null) "none" else "%02X%02X".format(r.status1.toInt() and 0xFF, r.status2.toInt() and 0xFF)

        private fun statusOf(raw: ByteArray): String =
            if (raw.size < 2) "none" else "%02X%02X".format(raw[raw.size - 2].toInt() and 0xFF, raw[raw.size - 1].toInt() and 0xFF)
    }
}

/**
 * The commands we send ourselves instead of through the library's command classes:
 *  - GetFileSettings / WriteData: see [fileCommMode] and [writeData].
 *  - WriteData (FULL) / ChangeFileSettings: the library's AESEncryptionMode.encryptData only pads when the
 *    length is not a multiple of 16, but the chip always expects ISO/IEC 9797-1 method 2 padding:
 *    AN12196 Table 17 writes 128 bytes as 144 encrypted bytes, and the library's WriteData would
 *    send 128 and be rejected. We pad first, so encryptData sees a whole number of blocks and adds
 *    nothing. (The library's ChangeKey for keys 1..4 always has 21 bytes, so it is used as is.)
 *  - ChangeKey for key 0 (case 2, AN12196 §5.16.2 / Table 26): data = new key ‖ version. The chip
 *    answers 91 00 without a MAC and ends the session. The library's ChangeKey then calls
 *    restartSession(), which re-authenticates with the *old* key 0 remembered from login: that
 *    must fail on the chip, adds a failed-authentication count, is ignored by restartSession
 *    (it discards the boolean), and a transport error at that moment would surface as an
 *    IOException indistinguishable from the ChangeKey itself failing. So we send the one command
 *    and mark the session ended.
 */
internal object ChipCommands {
    fun fitsOneWrite(n: Int) = n in 1..239 // FULL: Lc = 7 header + padded ciphertext + 8 MAC <= 255

    /**
     * GetFileSettings (F5) in CommMode.MAC inside the session (AN12196 Table 7), returning the
     * file's CommMode from FileOption bits 1..0 (00/10 plain, 01 MAC, 11 full). We read only those
     * bits rather than use the library's `FileSettings.decodeFromData`, which also parses the SDM
     * fields and reads the two SDMAccessRights bytes in the opposite order to its own encoder.
     * The library skips the response MAC check when fewer than 8 bytes come back, so a short
     * answer is refused here.
     */
    fun fileCommMode(comm: DnaCommunicator, fileNo: Int): CommunicationMode {
        check(comm.isLoggedIn) { "GetFileSettings without a session" }
        val r = comm.nxpMacCommand(0xF5.toByte(), byteArrayOf(fileNo.toByte()), null)
        r.throwUnlessSuccessful()
        val data = r.data ?: ByteArray(0)
        require(data.size >= 7) { "GetFileSettings returned ${data.size} bytes" }
        return when (data[1].toInt() and 0x03) {
            0x01 -> CommunicationMode.MAC
            0x03 -> CommunicationMode.FULL
            else -> CommunicationMode.PLAIN // 00, and 10 (also plain per the datasheet)
        }
    }

    /**
     * WriteData (8D) in [mode], which must be the file's CommMode (datasheet: the command uses the
     * CommMode of the file settings). Always sent inside the authenticated session, because the
     * file's write right after ChangeFileSettings is key 0.
     *  - FULL: the padded encrypted path ([encrypted]); AN12196 Table 17.
     *  - MAC: header ‖ plain data ‖ MACt, response MAC checked by the library (AN12196 §4.3).
     *  - PLAIN: `90 8D 00 00 Lc 02 offset(3) length(3) data 00`, no MAC either way (§4.2, Table 24).
     *
     * CmdCtr in plain mode: the library's `nxpPlainCommand` increments the command counter, and
     * that is right inside a session. The datasheet text isn't in this repo, and AN12196 §4.2
     * (Figure 8) only shows the framing, but AN12196's session B counts it: Table 21 (WriteData
     * FULL) runs at CmdCtr 0000, Table 23 (AuthenticateEV2NonFirst) keeps TI and CmdCtr, Table 24
     * (WriteData CommMode.PLAIN) follows, and Table 25 (ChangeKey) runs at CmdCtr 0200. Only
     * Table 24 is in between, so the plain command advanced the counter from 1 to 2. (This is the
     * DESFire EV2 rule: CmdCtr advances on every command/response pair in the session whatever
     * the CommMode.) Getting it wrong would show as 91 1E on the next MACed command.
     */
    fun writeData(comm: DnaCommunicator, mode: CommunicationMode, fileNo: Int, offset: Int, data: ByteArray) {
        require(fitsOneWrite(data.size)) { "WriteData too long for one APDU" }
        check(comm.isLoggedIn) { "WriteData without a session" }
        val header = byteArrayOf(fileNo.toByte(), *le3(offset), *le3(data.size))
        when (mode) {
            CommunicationMode.FULL -> encrypted(comm, 0x8D, header, data)
            CommunicationMode.MAC -> comm.nxpMacCommand(0x8D.toByte(), header, data).throwUnlessSuccessful()
            CommunicationMode.PLAIN, CommunicationMode.PLAIN_ALT ->
                comm.nxpPlainCommand(0x8D.toByte(), header, data).throwUnlessSuccessful()
        }
    }

    fun changeFileSettings(comm: DnaCommunicator, fileNo: Int, dataField: ByteArray) =
        encrypted(comm, 0x5F, byteArrayOf(fileNo.toByte()), dataField)

    fun changeKey0(comm: DnaCommunicator, newKey: ByteArray, version: Int) {
        check(comm.isLoggedIn && comm.activeKeyNumber == 0) { "ChangeKey 0 needs a key 0 session" }
        val data = newKey + byteArrayOf(version.toByte())
        try {
            encrypted(comm, 0xC4, byteArrayOf(0x00), data)
        } finally {
            data.fill(0)
        }
        comm.startEncryptedSession(null, 0, 0, null) // the chip has ended the session
    }

    /** Encrypted + MACed native command with the data padded here. Throws on a non-success status. */
    fun encrypted(comm: DnaCommunicator, ins: Int, header: ByteArray, data: ByteArray) {
        check(comm.isLoggedIn) { "Encrypted command without a session" }
        val p = iso9797Pad(data)
        try {
            comm.nxpEncryptedCommand(ins.toByte(), header, p).throwUnlessSuccessful()
        } finally {
            p.fill(0)
        }
    }

    /** Adds 0x80 then zeros up to a multiple of 16, always (at least one byte). */
    fun iso9797Pad(data: ByteArray): ByteArray = ByteArray((data.size / 16 + 1) * 16).also {
        data.copyInto(it)
        it[data.size] = 0x80.toByte()
    }

    private fun le3(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
}

/**
 * Order key 0 candidates so the likely key goes first and a rewrite doesn't cost the chip a failed
 * authentication (each one adds to TotFailCtr/SeqFailCtr, AN12196 §6.4). [key0Version] is from the
 * unauthenticated GetKeyVersion (null if the chip refused it):
 *  - at [targetVersion]: Piña's (non-zero) keys first, then the factory key;
 *  - at 0: the factory (all-zero) key first, then the others;
 *  - otherwise or unknown: the server's order.
 * The order within each group is kept.
 */
fun orderKey0Candidates(candidates: List<ByteArray>, key0Version: Int?, targetVersion: Int): List<ByteArray> {
    fun isFactory(k: ByteArray) = k.all { it == 0.toByte() }
    return when (key0Version) {
        null -> candidates
        0 -> candidates.filter(::isFactory) + candidates.filterNot(::isFactory)
        targetVersion -> candidates.filterNot(::isFactory) + candidates.filter(::isFactory)
        else -> candidates
    }
}

enum class KeyAction { CHANGE, SKIP }

/**
 * State detection for one key slot (guide §3). [currentVersion] is from GetKeyVersion.
 *  - Keys 1 and 2: version 0 means factory (old key = zeros) → change; the target version means
 *    Piña already wrote it → skip; anything else is someone else's key, whose old value we don't
 *    know, so ChangeKey (which XORs with the old key) can't be sent → fail.
 *    (If a slot claims version 0 but isn't the zero key, the chip's CRC check on the new key fails
 *    and the key is left unchanged.)
 *  - Key 0: we are authenticated with it, so no old key is needed. Skip only when the key we
 *    authenticated with is Piña's key 0 and the version is already the target; otherwise change.
 */
fun planKeyChange(slot: Int, currentVersion: Int, targetVersion: Int, authKeyIsTarget: Boolean): KeyAction {
    require(slot in 0..2)
    if (slot == 0) {
        return if (authKeyIsTarget && currentVersion == targetVersion) KeyAction.SKIP else KeyAction.CHANGE
    }
    return when (currentVersion) {
        0 -> KeyAction.CHANGE
        targetVersion -> KeyAction.SKIP
        else -> throw TagWriteException(
            if (slot == 1) Step.KEY1 else Step.KEY2,
            "Key $slot is at version $currentVersion, which neither the factory nor Piña set; it can't be changed safely.",
        )
    }
}
