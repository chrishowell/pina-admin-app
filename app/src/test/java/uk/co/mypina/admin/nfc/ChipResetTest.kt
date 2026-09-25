package uk.co.mypina.admin.nfc

import net.bplearning.ntag424.util.ByteUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import uk.co.mypina.admin.api.PersonaliseResponse
import uk.co.mypina.admin.api.SdmSettings
import uk.co.mypina.admin.api.TagInfo
import uk.co.mypina.admin.api.TagKeys
import uk.co.mypina.admin.nfc.An12196 as V
import java.util.Random

/**
 * [ChipReset] against a scripted chip. Authentication with this server's derived key 0 is played
 * by [RefAuthChip], and every command in the session is checked by [RefSession] (MAC verified,
 * payload decrypted and compared). The factory-key authentication is AN12196 Table 14 byte for
 * byte. GetKeyVersion and select are exact APDUs. Anything else sent (NDEF, SDM, a second
 * authentication) is out of script and fails the test.
 */
class ChipResetTest {
    private lateinit var savedRandom: Random
    private val steps = mutableListOf<Pair<Step, StepState>>()
    private val personaliseCalls = mutableListOf<String>()

    @Before fun fixRandom() {
        savedRandom = ByteUtil.random
        ByteUtil.random = FixedRandom(V.T14_RND_A.hexToBytes())
    }

    @After fun restoreRandom() { ByteUtil.random = savedRandom }

    private val uid = "04958CAA5C5E80"
    private val key0 = V.T26_NEW_KEY
    private val key1 = "00112233445566778899AABBCCDDEEFF"
    private val key2 = V.T25_NEW_KEY
    private val zeros = ByteArray(16)

    private fun job() = PersonaliseResponse(
        tag = TagInfo(id = "tag-1", code = "ABC1234", shopName = "Demo shop"),
        url = V.NDEF_URL,
        ndefFileHex = V.T17_DATA,
        sdm = SdmSettings("40", "00E0", "C1", "F121", 0x20, 0x43, 0x43),
        keys = TagKeys(key0, key1, key2, 1),
        key0Candidates = listOf(V.ZERO_KEY, key0),
    )

    private fun reset(fake: FakeTransceiver) = ChipReset(
        transceive = fake,
        personalise = { personaliseCalls += it; job() },
    )

    private fun onStep(s: Step, st: StepState) { steps += s to st }

    // ---- script pieces ----

    private fun FakeTransceiver.select() = expect("Table 22 select", V.T22_SELECT, V.T22_RESP)

    private fun FakeTransceiver.keyVersions(v0: Int, v1: Int, v2: Int) = apply {
        listOf(v0, v1, v2).forEachIndexed { k, v -> expect("GetKeyVersion $k", "90640000010${k}00", "%02X9100".format(v)) }
    }

    /** AuthenticateEV2First key 0 with this server's derived key 0; [onSession] gets the session. */
    private fun FakeTransceiver.authDerived(onSession: (RefSession) -> Unit): FakeTransceiver {
        val chip = RefAuthChip(key0.hexToBytes(), V.T14_RND_B.hexToBytes(), "11223344".hexToBytes())
        return expect("auth derived part 1", V.T14_CMD1, chip.part1().toHex())
            .expectChecked("auth derived part 2") { apdu -> chip.part2(apdu).also { onSession(chip.session()) } }
    }

    private fun FakeTransceiver.changeKey(ref: () -> RefSession, slot: Int, expectedPlain: ByteArray, mac: Boolean) =
        expectChecked("ChangeKey $slot") { apdu ->
            val plain = ref().verifyCommand(apdu, 0xC4, byteArrayOf(slot.toByte()))
            assertEquals("ChangeKey $slot payload", Ref.pad(expectedPlain).toHex(), plain.toHex())
            if (mac) ref().respond() else ref().respondBare()
        }

    private fun xor(a: ByteArray, b: ByteArray) = ByteArray(16) { (a[it].toInt() xor b[it].toInt()).toByte() }

    /** ChangeKey case 1 (keys 1..4): (new ⊕ old) ‖ version ‖ JAMCRC(new). */
    private fun caseOne(old: String, new: ByteArray, version: Int) =
        xor(new, old.hexToBytes()) + byteArrayOf(version.toByte()) + Ref.jamCrc(new)

    /** ChangeKey case 2 (key 0): new ‖ version. */
    private fun caseTwo(new: ByteArray, version: Int) = new + byteArrayOf(version.toByte())

    // ---- tests ----

    @Test fun fullReset_fromServerKeys_changesKeys1And2ThenKey0_toZerosVersion0() {
        lateinit var ref: RefSession
        val fake = FakeTransceiver()
            .select()
            .keyVersions(1, 1, 1)
            .authDerived { ref = it }
            .changeKey({ ref }, 1, caseOne(old = key1, new = zeros, version = 0), mac = true)
            .changeKey({ ref }, 2, caseOne(old = key2, new = zeros, version = 0), mac = true)
            .changeKey({ ref }, 0, caseTwo(zeros, 0), mac = false)
            .select()
            .keyVersions(0, 0, 0)

        val result = reset(fake).run(uid.hexToBytes(), ::onStep)

        fake.assertDone()
        assertEquals(ResetResult(uid), result)
        assertEquals(listOf(uid), personaliseCalls)
        assertEquals(RESET_STEPS.flatMap { listOf(it to StepState.RUNNING, it to StepState.DONE) }, steps)
        // The new key is zeros, so new ⊕ old is the old (derived) key itself.
        assertEquals(key1, caseOne(key1, zeros, 0).copyOfRange(0, 16).toHex())
        assertEquals("CRC of the zero key", Ref.jamCrc(zeros).toHex(), caseOne(key2, zeros, 0).copyOfRange(17, 21).toHex())
        assertEquals("ChangeKey 1, 2 at CmdCtr 0, 1; key 0 at 2", 3, ref.ctr)
    }

    @Test fun alreadyFactoryChip_authenticatesWithZeros_sendsNoChangeKey() {
        val fake = FakeTransceiver()
            .select()
            .keyVersions(0, 0, 0)
            .expect("Table 14 part 1", V.T14_CMD1, V.T14_RESP1)
            .expect("Table 14 part 2", V.T14_CMD2, V.T14_RESP2)
            .select()
            .keyVersions(0, 0, 0)

        val result = reset(fake).run(uid.hexToBytes(), ::onStep)

        fake.assertDone()
        assertEquals(uid, result.uid)
        assertTrue("no ChangeKey sent", fake.sent.none { it.startsWith("90C4") })
        for (s in listOf(Step.KEY1, Step.KEY2, Step.KEY0)) {
            assertEquals("$s skipped: DONE without RUNNING", listOf(s to StepState.DONE), steps.filter { it.first == s })
        }
    }

    @Test fun factoryKey0_withServerKeys1And2_resetsThemInTheFactorySession() {
        // Key 0 already factory (e.g. an interrupted reset or write), keys 1 and 2 still this server's.
        val ref = RefSession(V.A_ENC, V.A_MAC, V.A_TI, ctr = 0)
        val fake = FakeTransceiver()
            .select()
            .keyVersions(0, 1, 1)
            .expect("Table 14 part 1", V.T14_CMD1, V.T14_RESP1)
            .expect("Table 14 part 2", V.T14_CMD2, V.T14_RESP2)
            .changeKey({ ref }, 1, caseOne(key1, zeros, 0), mac = true)
            .changeKey({ ref }, 2, caseOne(key2, zeros, 0), mac = true)
            .select()
            .keyVersions(0, 0, 0)

        reset(fake).run(uid.hexToBytes(), ::onStep)

        fake.assertDone()
        assertEquals(listOf(Step.KEY0 to StepState.DONE), steps.filter { it.first == Step.KEY0 })
    }

    @Test fun foreignKey0_triesOnlyTheDerivedKey_andSaysWhichServer() {
        val fake = FakeTransceiver()
            .select()
            .keyVersions(1, 1, 1)
            .expect("auth derived part 1", V.T14_CMD1, V.T14_RESP1)
            .expectChecked("auth derived part 2") { apdu ->
                assertTrue("derived key, not zeros", apdu.toHex() != V.T14_CMD2)
                "91AE".hexToBytes()
            }

        val e = expectFailure { reset(fake).run(uid.hexToBytes(), ::onStep) }

        fake.assertDone() // one authentication only: the factory key is never tried
        assertEquals(Step.AUTHENTICATED, e.step)
        assertEquals("This chip was written by a different server; reset it from that server.", e.message)
    }

    @Test fun partiallyResetChip_sendsOnlyTheTwoRemainingChangeKeys() {
        // Key 1 already back at 0 from an earlier attempt; keys 2 and 0 still at 1.
        lateinit var ref: RefSession
        val fake = FakeTransceiver()
            .select()
            .keyVersions(1, 0, 1)
            .authDerived { ref = it }
            .changeKey({ ref }, 2, caseOne(key2, zeros, 0), mac = true)
            .changeKey({ ref }, 0, caseTwo(zeros, 0), mac = false)
            .select()
            .keyVersions(0, 0, 0)

        reset(fake).run(uid.hexToBytes(), ::onStep)

        fake.assertDone()
        assertEquals(2, fake.sent.count { it.startsWith("90C4") })
        assertEquals(listOf(Step.KEY1 to StepState.DONE), steps.filter { it.first == Step.KEY1 })
    }

    @Test fun versionStillOneAfterReset_failsAtVerified() {
        lateinit var ref: RefSession
        val fake = FakeTransceiver()
            .select()
            .keyVersions(1, 1, 1)
            .authDerived { ref = it }
            .changeKey({ ref }, 1, caseOne(key1, zeros, 0), mac = true)
            .changeKey({ ref }, 2, caseOne(key2, zeros, 0), mac = true)
            .changeKey({ ref }, 0, caseTwo(zeros, 0), mac = false)
            .select()
            .keyVersions(0, 1, 0)

        val e = expectFailure { reset(fake).run(uid.hexToBytes(), ::onStep) }

        fake.assertDone()
        assertEquals(Step.VERIFIED, e.step)
        assertTrue(e.message, e.message!!.contains("Key 1"))
    }

    @Test fun unknownKey2Version_failsWithoutChangingIt() {
        lateinit var ref: RefSession
        val fake = FakeTransceiver()
            .select()
            .keyVersions(1, 1, 7)
            .authDerived { ref = it }
            .changeKey({ ref }, 1, caseOne(key1, zeros, 0), mac = true)

        val e = expectFailure { reset(fake).run(uid.hexToBytes(), ::onStep) }

        fake.assertDone()
        assertEquals(Step.KEY2, e.step)
    }

    @Test fun randomId_isRefusedBeforeTouchingTheChip() {
        val fake = FakeTransceiver()
        val e = expectFailure { reset(fake).run("08A1B2C3".hexToBytes(), ::onStep) }
        assertEquals(Step.READ_UID, e.step)
        assertTrue(fake.sent.isEmpty())
        assertTrue(personaliseCalls.isEmpty())
    }

    @Test fun keyMaterialIsNotInFailureMessages() {
        val fake = FakeTransceiver()
            .select()
            .keyVersions(1, 1, 1)
            .authDerived { }
            .expectChecked("ChangeKey 1 refused") { "919E".hexToBytes() }
        val e = expectFailure { reset(fake).run(uid.hexToBytes(), ::onStep) }
        assertEquals(Step.KEY1, e.step)
        for (k in listOf(key0, key1, key2)) assertTrue(!e.message!!.contains(k))
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
