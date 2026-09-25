package uk.co.mypina.admin.nfc

import net.bplearning.ntag424.util.ByteUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import uk.co.mypina.admin.api.PersonaliseResponse
import uk.co.mypina.admin.api.PersonalisedResponse
import uk.co.mypina.admin.api.SdmSettings
import uk.co.mypina.admin.api.TagInfo
import uk.co.mypina.admin.api.TagKeys
import uk.co.mypina.admin.nfc.An12196 as V
import java.util.Random

/**
 * The whole writer sequence against a scripted chip.
 *
 * Byte for byte from AN12196: select (Table 22), AuthenticateEV2First (Table 14). The job is built
 * so the writer must produce those bytes (key 0 = zeros tried first, RndA fixed); the NDEF file is
 * Table 17's 128 bytes and the SDM fields are Table 18's.
 *
 * After authentication the writer reads file 02's settings (GetFileSettings, CommMode.MAC, CmdCtr
 * 0), so WriteData runs at CmdCtr 1 and ChangeFileSettings at 2: Tables 17/18 (CmdCtr 0/1) can't be
 * replayed here. They are replayed byte for byte through the same [ChipCommands] in
 * [An12196VectorsTest]; here every command in session A is verified by [RefSession] (MAC checked,
 * payload decrypted where encrypted and compared), which is pinned to Tables 14/17/18/23/25/26.
 *
 * Also exact APDUs with synthetic responses: GetKeyVersion (plain, unauthenticated), plain
 * WriteData, and the final plain ReadData.
 */
class ChipSequenceTest {
    private lateinit var savedRandom: Random
    private val steps = mutableListOf<Pair<Step, StepState>>()
    private val personaliseCalls = mutableListOf<String>()
    private val personalisedCalls = mutableListOf<Pair<String, String>>()

    @Before fun fixRandom() {
        savedRandom = ByteUtil.random
        ByteUtil.random = FixedRandom(V.T14_RND_A.hexToBytes())
    }

    @After fun restoreRandom() { ByteUtil.random = savedRandom }

    private val uid = "04958CAA5C5E80"
    private val key0 = V.T26_NEW_KEY
    private val key1 = "00112233445566778899AABBCCDDEEFF"
    private val key2 = V.T25_NEW_KEY
    private val eMirror = "EF963FF7828658A599F3041510671E88"
    private val cMirror = "94EED9EE65337086"

    private fun job(key0: String = this.key0, candidates: List<String> = listOf(V.ZERO_KEY, key0)) = PersonaliseResponse(
        tag = TagInfo(id = "tag-1", code = "ABC1234", shopName = "Demo shop"),
        url = V.NDEF_URL,
        ndefFileHex = V.T17_DATA,
        sdm = SdmSettings("40", "00E0", "C1", "F121", 0x20, 0x43, 0x43),
        keys = TagKeys(key0, key1, key2, 1),
        key0Candidates = candidates,
    )

    private fun sequence(fake: FakeTransceiver, job: PersonaliseResponse = job()) = ChipSequence(
        transceive = fake,
        personalise = { personaliseCalls += it; job },
        personalised = { u, url -> personalisedCalls += u to url; PersonalisedResponse(ok = true, counter = 1) },
    )

    private fun onStep(s: Step, st: StepState) { steps += s to st }

    // ---- script pieces ----

    private fun FakeTransceiver.select() = expect("Table 22 select", V.T22_SELECT, V.T22_RESP)

    private fun FakeTransceiver.keyVersions(v0: Int, v1: Int, v2: Int) = apply {
        listOf(v0, v1, v2).forEachIndexed { k, v -> expect("GetKeyVersion $k", "90640000010${k}00", "%02X9100".format(v)) }
    }

    private fun FakeTransceiver.table14() =
        expect("Table 14 part 1", V.T14_CMD1, V.T14_RESP1).expect("Table 14 part 2", V.T14_CMD2, V.T14_RESP2)

    /** GetFileSettings file 02 in the session, answering FileOption [option] (low bits = CommMode). */
    private fun FakeTransceiver.fileSettings(ref: RefSession, option: Int) = expectChecked("GetFileSettings 02") { apdu ->
        assertEquals(0, ref.verifyCommand(apdu, 0xF5, "02".hexToBytes()).size)
        ref.respondMac("00%02XE0EE000100".format(option).hexToBytes())
    }

    private val writeHeader = "02000000800000"

    /** WriteData of Table 17's data in the file's CommMode (0 plain, 1 MAC, 3 full). */
    private fun FakeTransceiver.writeData(ref: RefSession, mode: Int) = when (mode) {
        0 -> expectChecked("WriteData plain") { apdu ->
            // 90 8D 00 00 Lc 02 000000 800000 <data> 00: no encryption, no MAC.
            assertEquals("908D0000" + "%02X".format(7 + 0x80) + writeHeader + V.T17_DATA + "00", apdu.toHex())
            ref.respondPlain()
        }
        1 -> expectChecked("WriteData MAC") { apdu ->
            assertEquals(V.T17_DATA, ref.verifyCommand(apdu, 0x8D, writeHeader.hexToBytes(), decrypt = false).toHex())
            ref.respond()
        }
        3 -> expectChecked("WriteData full") { apdu ->
            assertEquals(Ref.pad(V.T17_DATA.hexToBytes()).toHex(), ref.verifyCommand(apdu, 0x8D, writeHeader.hexToBytes()).toHex())
            ref.respond()
        }
        else -> error("mode $mode")
    }

    private fun FakeTransceiver.changeFileSettings(ref: RefSession) = expectChecked("ChangeFileSettings") { apdu ->
        assertEquals(Ref.pad(V.T18_DATA.hexToBytes()).toHex(), ref.verifyCommand(apdu, 0x5F, "02".hexToBytes()).toHex())
        ref.respond()
    }

    /** GetFileSettings, WriteData in [mode], ChangeFileSettings. */
    private fun FakeTransceiver.urlAndSdm(ref: RefSession, mode: Int = 0) =
        fileSettings(ref, 0x40 or mode).writeData(ref, mode).changeFileSettings(ref)

    private fun sessionA() = RefSession(V.A_ENC, V.A_MAC, V.A_TI, ctr = 0)

    private fun FakeTransceiver.readBack() =
        expect("ReadData file 02 plain", "90AD0000070200000080000000", mirroredFile() + "9100")

    private fun mirroredFile(): String {
        val f = V.T17_DATA.hexToBytes()
        eMirror.toByteArray().copyInto(f, 0x20)
        cMirror.toByteArray().copyInto(f, 0x43)
        return f.toHex()
    }

    private fun expectedUrl() = V.NDEF_URL.replace("e=" + "0".repeat(32), "e=$eMirror").replace("c=" + "0".repeat(16), "c=$cMirror")

    private fun FakeTransceiver.changeKey(ref: RefSession, slot: Int, expectedPlain: ByteArray, mac: Boolean) =
        expectChecked("ChangeKey $slot") { apdu ->
            val plain = ref.verifyCommand(apdu, 0xC4, byteArrayOf(slot.toByte()))
            assertEquals("ChangeKey $slot payload", Ref.pad(expectedPlain).toHex(), plain.toHex())
            if (mac) ref.respond() else ref.respondBare()
        }

    private fun caseOnePayload(newKey: String) = newKey.hexToBytes() + byteArrayOf(0x01) + Ref.jamCrc(newKey.hexToBytes())

    // ---- tests ----

    @Test fun factoryChip_fullSequence() {
        // Factory file 02 is plain (FileOption 00), so WriteData goes plain inside the session.
        val ref = sessionA()
        val fake = FakeTransceiver()
            .select()
            .keyVersions(0, 0, 0)
            .table14()
            .fileSettings(ref, 0x00)
            .writeData(ref, 0)
            .changeFileSettings(ref)
            .changeKey(ref, 1, caseOnePayload(key1), mac = true)   // old key = zeros, so new ⊕ old = new
            .changeKey(ref, 2, caseOnePayload(key2), mac = true)
            .changeKey(ref, 0, key0.hexToBytes() + byteArrayOf(0x01), mac = false)
            .select()
            .readBack()

        val result = sequence(fake).run(uid.hexToBytes(), ::onStep)

        fake.assertDone()
        assertEquals(listOf(uid), personaliseCalls)
        assertEquals(listOf(uid to expectedUrl()), personalisedCalls)
        assertEquals(WriteResult("ABC1234", "Demo shop", uid, 1), result)
        assertEquals(Step.entries.flatMap { listOf(it to StepState.RUNNING, it to StepState.DONE) }, steps)
    }

    @Test fun alreadyWrittenChip_skipsKeyChanges_rewritesUrlAndSdm() {
        // All three slots already at version 1; key 0 is the job's key 0 (zeros here, so Table 14 applies).
        val fake = FakeTransceiver()
            .select()
            .keyVersions(1, 1, 1)
            .table14()
            .urlAndSdm(sessionA())
            .select()
            .readBack()

        val result = sequence(fake, job(key0 = V.ZERO_KEY, candidates = listOf(V.ZERO_KEY))).run(uid.hexToBytes(), ::onStep)

        fake.assertDone()
        assertEquals(uid, result.uid)
        for (s in listOf(Step.KEY1, Step.KEY2, Step.KEY0)) {
            assertEquals("$s skipped: DONE without RUNNING", listOf(s to StepState.DONE), steps.filter { it.first == s })
        }
    }

    @Test fun halfWrittenChip_changesOnlyKey0() {
        // Keys 1 and 2 done on an earlier attempt, key 0 still factory.
        val ref = sessionA()
        val fake = FakeTransceiver()
            .select()
            .keyVersions(0, 1, 1)
            .table14()
            .urlAndSdm(ref)
            .changeKey(ref, 0, key0.hexToBytes() + byteArrayOf(0x01), mac = false)
            .select()
            .readBack()

        sequence(fake).run(uid.hexToBytes(), ::onStep)
        fake.assertDone()
    }

    @Test fun foreignKey0_failsWithClearMessage() {
        // Key 0 claims the target version, so Piña's key is tried first, then the factory key.
        val fake = FakeTransceiver()
            .select()
            .keyVersions(1, 1, 1)
            .expect("auth derived part 1", V.T14_CMD1, V.T14_RESP1)
            .expectChecked("auth derived part 2") { apdu ->
                assertEquals("90AF000020", apdu.toHex().substring(0, 10))
                assertEquals(5 + 32 + 1, apdu.size)
                assertTrue("derived key first, not zeros", apdu.toHex() != V.T14_CMD2)
                "91AE".hexToBytes()
            }
            .expect("auth zeros part 1", V.T14_CMD1, V.T14_RESP1)
            .expect("auth zeros part 2", V.T14_CMD2, "91AE")

        val e = expectFailure { sequence(fake).run(uid.hexToBytes(), ::onStep) }
        assertEquals(Step.AUTHENTICATED, e.step)
        assertEquals("This chip's key 0 is not factory or Piña's; it cannot be written.", e.message)
        fake.assertDone()
    }

    @Test fun authenticationDelay_saysWait() {
        val fake = FakeTransceiver().select().keyVersions(0, 0, 0).expect("auth part 1", V.T14_CMD1, "91AD")
        val e = expectFailure { sequence(fake).run(uid.hexToBytes(), ::onStep) }
        assertEquals(Step.AUTHENTICATED, e.step)
        assertTrue(e.message!!.contains("Wait"))
    }

    @Test fun unknownKey1Version_failsBeforeTouchingKeys() {
        val fake = FakeTransceiver()
            .select()
            .keyVersions(0, 7, 0)
            .table14()
            .urlAndSdm(sessionA())
        val e = expectFailure { sequence(fake).run(uid.hexToBytes(), ::onStep) }
        assertEquals(Step.KEY1, e.step)
        fake.assertDone()
    }

    @Test fun chipStatusError_namesTheStep() {
        val ref = sessionA()
        val fake = FakeTransceiver()
            .select()
            .keyVersions(0, 0, 0)
            .table14()
            .fileSettings(ref, 0x00)
            .expectChecked("WriteData refused") { "919D".hexToBytes() }
        val e = expectFailure { sequence(fake).run(uid.hexToBytes(), ::onStep) }
        assertEquals(Step.URL_WRITTEN, e.step)
        assertTrue(e.message, e.message!!.contains("919D"))
    }

    @Test fun randomId_readsUidAfterFactoryAuth_thenContinuesTheSession() {
        val ref = RefSession(V.A_ENC, V.A_MAC, V.A_TI, ctr = 0)
        val fake = FakeTransceiver()
            .select()
            .keyVersions(0, 0, 0)
            .table14()
            .expectChecked("GetCardUid") { apdu ->
                assertEquals(0, ref.verifyCommand(apdu, 0x51, ByteArray(0)).size)
                ref.respond(uid.hexToBytes())
            }
            .urlAndSdm(ref, mode = 3) // GetFileSettings at CmdCtr 1, WriteData 2, ChangeFileSettings 3
            .changeKey(ref, 1, caseOnePayload(key1), mac = true)
            .changeKey(ref, 2, caseOnePayload(key2), mac = true)
            .changeKey(ref, 0, key0.hexToBytes() + byteArrayOf(0x01), mac = false)
            .select()
            .readBack()

        val result = sequence(fake).run("08A1B2C3".hexToBytes(), ::onStep)

        fake.assertDone()
        assertEquals(listOf(uid), personaliseCalls)
        assertEquals(uid, result.uid)
    }

    @Test fun randomId_withNonFactoryKey0_fails() {
        val fake = FakeTransceiver()
            .select()
            .keyVersions(1, 1, 1)
            .expect("auth part 1", V.T14_CMD1, V.T14_RESP1)
            .expect("auth part 2", V.T14_CMD2, "91AE")
        val e = expectFailure { sequence(fake).run("08A1B2C3".hexToBytes(), ::onStep) }
        assertEquals(Step.READ_UID, e.step)
        assertTrue(personaliseCalls.isEmpty())
    }

    @Test fun readBackForAnotherUrl_isRejected() {
        val fake = FakeTransceiver()
            .select()
            .keyVersions(1, 1, 1)
            .table14()
            .urlAndSdm(sessionA())
            .select()
            .expect("ReadData", "90AD0000070200000080000000", V.T17_DATA.replace("63686F6F7365", "6368656573") + "009100")
        val e = expectFailure { sequence(fake, job(key0 = V.ZERO_KEY, candidates = listOf(V.ZERO_KEY))).run(uid.hexToBytes(), ::onStep) }
        assertEquals(Step.VERIFIED, e.step)
        assertTrue(personalisedCalls.isEmpty())
    }

    @Test fun keyMaterialIsNotInFailureMessages() {
        val fake = FakeTransceiver().select().keyVersions(0, 0, 0).table14()
            .fileSettings(sessionA(), 0x00).expectChecked("WriteData") { "911E".hexToBytes() }
        val e = expectFailure { sequence(fake).run(uid.hexToBytes(), ::onStep) }
        for (k in listOf(key0, key1, key2)) assertTrue(!e.message!!.contains(k))
    }

    // ---- WriteData follows the file's CommMode ----

    /** Already-written chip (keys skipped) whose file 02 reports CommMode [mode]. */
    private fun rewriteWithFileMode(mode: Int) {
        val ref = sessionA()
        val fake = FakeTransceiver()
            .select()
            .keyVersions(1, 1, 1)
            .table14()
            .urlAndSdm(ref, mode)
            .select()
            .readBack()
        sequence(fake, job(key0 = V.ZERO_KEY, candidates = listOf(V.ZERO_KEY))).run(uid.hexToBytes(), ::onStep)
        fake.assertDone()
        assertEquals("GetFileSettings, WriteData, ChangeFileSettings each advanced CmdCtr", 3, ref.ctr)
    }

    @Test fun writeData_plainFile_sentPlainInsideTheSession() = rewriteWithFileMode(0)

    @Test fun writeData_macFile_sentWithMac() = rewriteWithFileMode(1)

    @Test fun writeData_fullFile_sentEncrypted() = rewriteWithFileMode(3)

    @Test fun getFileSettingsWithoutData_failsAtUrlWritten() {
        val fake = FakeTransceiver().select().keyVersions(1, 1, 1).table14()
            .expectChecked("GetFileSettings, bare 9100") { "9100".hexToBytes() }
        val e = expectFailure { sequence(fake, job(key0 = V.ZERO_KEY, candidates = listOf(V.ZERO_KEY))).run(uid.hexToBytes(), ::onStep) }
        assertEquals(Step.URL_WRITTEN, e.step)
        fake.assertDone()
    }

    // ---- key 0 candidate order ----

    @Test fun key0AtTargetVersion_triesDerivedKeyFirst_noFailedAuth() {
        // Server order is factory first; GetKeyVersion says 1, so Piña's key 0 goes first and is the
        // only authentication the chip sees. Then everything is skipped except URL and SDM.
        val chip = RefAuthChip(key0.hexToBytes(), V.T14_RND_B.hexToBytes(), "11223344".hexToBytes())
        lateinit var ref: RefSession
        val fake = FakeTransceiver()
            .select()
            .keyVersions(1, 1, 1)
            .expect("auth derived part 1", V.T14_CMD1, chip.part1().toHex())
            .expectChecked("auth derived part 2") { apdu -> chip.part2(apdu).also { ref = chip.session() } }
            .expectChecked("GetFileSettings 02") { apdu ->
                assertEquals(0, ref.verifyCommand(apdu, 0xF5, "02".hexToBytes()).size)
                ref.respondMac("0040E0EE000100".hexToBytes())
            }
            .expectChecked("WriteData plain") { apdu ->
                assertEquals("908D000087$writeHeader${V.T17_DATA}00", apdu.toHex())
                ref.respondPlain()
            }
            .expectChecked("ChangeFileSettings") { apdu ->
                assertEquals(Ref.pad(V.T18_DATA.hexToBytes()).toHex(), ref.verifyCommand(apdu, 0x5F, "02".hexToBytes()).toHex())
                ref.respond()
            }
            .select()
            .readBack()

        sequence(fake, job(candidates = listOf(V.ZERO_KEY, key0))).run(uid.hexToBytes(), ::onStep)

        fake.assertDone()
        for (s in listOf(Step.KEY1, Step.KEY2, Step.KEY0)) {
            assertEquals("$s skipped", listOf(s to StepState.DONE), steps.filter { it.first == s })
        }
    }

    @Test fun key0AtVersion0_triesFactoryKeyFirst() {
        // Server order is derived first; GetKeyVersion says 0, so zeros go first (Table 14 succeeds).
        val ref = sessionA()
        val fake = FakeTransceiver()
            .select()
            .keyVersions(0, 1, 1)
            .table14()
            .urlAndSdm(ref)
            .changeKey(ref, 0, key0.hexToBytes() + byteArrayOf(0x01), mac = false)
            .select()
            .readBack()
        sequence(fake, job(candidates = listOf(key0, V.ZERO_KEY))).run(uid.hexToBytes(), ::onStep)
        fake.assertDone()
    }

    private fun expectFailure(block: () -> Unit): TagWriteException {
        try {
            block()
        } catch (e: TagWriteException) {
            return e
        }
        fail("Expected a TagWriteException")
        error("unreachable")
    }
}
