package uk.co.mypina.admin.nfc

import net.bplearning.ntag424.CommunicationMode
import net.bplearning.ntag424.DnaCommunicator
import net.bplearning.ntag424.command.ChangeKey
import net.bplearning.ntag424.command.GetCardUid
import net.bplearning.ntag424.command.WriteData
import net.bplearning.ntag424.encryptionmode.AESEncryptionMode
import net.bplearning.ntag424.util.ByteUtil
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uk.co.mypina.admin.nfc.An12196 as V
import java.util.Random
import javax.crypto.spec.SecretKeySpec

/**
 * 1. Pins the independent reference crypto ([Ref]) to AN12196 values, so it can be trusted where
 *    the application note has no vector.
 * 2. Replays the vectors against the library and our [ChipCommands] in isolation: Table 14
 *    (auth), 25 (library ChangeKey, key 2), 26 (our ChangeKey 0), 28 (library GetCardUid).
 * 3. Characterises the library padding bug that [ChipCommands] works around (Table 17).
 * 4. GetFileSettings in MAC mode (Table 7) and WriteData in the file's CommMode: plain (Table 24,
 *    with the CmdCtr it consumes checked against Table 25), MAC (checked by [RefSession]).
 */
class An12196VectorsTest {
    private lateinit var savedRandom: Random

    @Before fun saveRandom() { savedRandom = ByteUtil.random }
    @After fun restoreRandom() { ByteUtil.random = savedRandom }

    // ---- reference crypto pinned to the application note ----

    @Test fun refSessionKeysMatchTable14() {
        val (enc, mac) = Ref.sessionKeys(V.ZERO_KEY.hexToBytes(), V.T14_RND_A.hexToBytes(), V.T14_RND_B.hexToBytes())
        assertEquals(V.A_ENC, enc.toHex())
        assertEquals(V.A_MAC, mac.toHex())
    }

    @Test fun refSessionKeysMatchTable23() {
        val (enc, mac) = Ref.sessionKeys(V.ZERO_KEY.hexToBytes(), V.T23_RND_A.hexToBytes(), V.T23_RND_B.hexToBytes())
        assertEquals(V.B_ENC, enc.toHex())
        assertEquals(V.B_MAC, mac.toHex())
    }

    @Test fun refIvsAndCmacsMatchTables17_25_26() {
        assertEquals(V.T17_IVC, RefSession(V.A_ENC, V.A_MAC, V.A_TI, 0).ivCmd().toHex())
        assertEquals(V.T25_IVE, RefSession(V.B_ENC, V.B_MAC, V.B_TI, 2).ivCmd().toHex())
        assertEquals(V.T26_IVC, RefSession(V.B_ENC, V.B_MAC, V.B_TI, 3).ivCmd().toHex())
        assertEquals(V.T17_CMAC, Ref.cmac(V.A_MAC.hexToBytes(), ("8D0000" + V.A_TI + "02000000800000" + V.T17_ENC).hexToBytes()).toHex())
        assertEquals(V.T25_CMAC, Ref.cmac(V.B_MAC.hexToBytes(), ("C40200" + V.B_TI + "02" + V.T25_ENC).hexToBytes()).toHex())
        assertEquals(V.T26_CMAC, Ref.cmac(V.B_MAC.hexToBytes(), ("C40300" + V.B_TI + "00" + V.T26_ENC).hexToBytes()).toHex())
        assertEquals(V.T25_CRC, Ref.jamCrc(V.T25_NEW_KEY.hexToBytes()).toHex())
    }

    @Test fun refSessionVerifiesAndDecryptsTable17And18() {
        val a = RefSession(V.A_ENC, V.A_MAC, V.A_TI, 0)
        val plain17 = a.verifyCommand(V.T17_CMD.hexToBytes(), 0x8D, "02000000800000".hexToBytes())
        assertEquals(Ref.pad(V.T17_DATA.hexToBytes()).toHex(), plain17.toHex())
        assertEquals(V.T17_RESP, a.respond().toHex())
        val plain18 = a.verifyCommand(V.T18_CMD.hexToBytes(), 0x5F, "02".hexToBytes())
        assertEquals(Ref.pad(V.T18_DATA.hexToBytes()).toHex(), plain18.toHex())
        assertEquals(V.T18_RESP, a.respond().toHex())
    }

    // ---- library + ChipCommands replayed byte for byte ----

    @Test fun table14AuthenticateEV2First() {
        ByteUtil.random = FixedRandom(V.T14_RND_A.hexToBytes())
        val fake = FakeTransceiver()
            .expect("Table 14 part 1", V.T14_CMD1, V.T14_RESP1)
            .expect("Table 14 part 2", V.T14_CMD2, V.T14_RESP2)
        val comm = communicator(fake)
        assertTrue(AESEncryptionMode.authenticateEV2(comm, 0, ByteArray(16)))
        assertEquals(V.A_TI, comm.activeTransactionIdentifier.toHex())
        assertEquals(0, comm.commandCounter)
        fake.assertDone()
    }

    @Test fun table25LibraryChangeKey2AndTable26OurChangeKey0() {
        val fake = FakeTransceiver()
            .expect("Table 25 ChangeKey 2", V.T25_CMD, V.T25_RESP)
            .expect("Table 26 ChangeKey 0", V.T26_CMD, V.T26_RESP)
        val comm = communicator(fake)
        // Session B: keys from Table 23's RndA/RndB with key 0 = zeros, TI from Table 19, CmdCtr 2.
        val mode = AESEncryptionMode(comm, SecretKeySpec(ByteArray(16), "AES"), V.T23_RND_A.hexToBytes(), V.T23_RND_B.hexToBytes())
        comm.startEncryptedSession(mode, 0, 2, V.B_TI.hexToBytes())

        // Library ChangeKey (case 1): also validates the response MAC 203BB55D1089D587.
        ChangeKey.run(comm, 2, ByteArray(16), V.T25_NEW_KEY.hexToBytes(), 1)
        assertEquals(3, comm.commandCounter)

        // Our ChangeKey 0 (case 2): no re-authentication afterwards, session marked ended.
        ChipCommands.changeKey0(comm, V.T26_NEW_KEY.hexToBytes(), 1)
        assertFalse(comm.isLoggedIn)
        fake.assertDone()
    }

    @Test fun table28GetCardUid() {
        val fake = FakeTransceiver().expect("Table 28 GetCardUid", V.T28_CMD, V.T28_RESP)
        val comm = communicator(fake)
        comm.startEncryptedSession(FixedSessionMode(comm, V.T28_ENC.hexToBytes(), V.T28_MAC.hexToBytes()), 0, 0, V.T28_TI.hexToBytes())
        assertEquals(V.T28_UID, GetCardUid.run(comm).toHex())
        fake.assertDone()
    }

    // ---- why ChipCommands pads for itself ----

    @Test fun libraryWriteDataDoesNotPadWholeBlocks_soItCannotProduceTable17() {
        val fake = FakeTransceiver().expectChecked("library WriteData") { "9100".hexToBytes() }
        val comm = communicator(fake)
        val mode = AESEncryptionMode(comm, SecretKeySpec(ByteArray(16), "AES"), V.T14_RND_A.hexToBytes(), V.T14_RND_B.hexToBytes())
        comm.startEncryptedSession(mode, 0, 0, V.A_TI.hexToBytes())
        WriteData.run(comm, CommunicationMode.FULL, 2, V.T17_DATA.hexToBytes(), 0)
        val sent = fake.sent.single()
        assertNotEquals(V.T17_CMD, sent)
        assertEquals("Lc = 7 + 128 + 8: the 128 data bytes were encrypted without the mandatory padding block", "8F", sent.substring(8, 10))
    }

    @Test fun chipCommandsWriteDataProducesTable17() {
        val fake = FakeTransceiver().expect("Table 17 WriteData", V.T17_CMD, V.T17_RESP)
        val comm = communicator(fake)
        val mode = AESEncryptionMode(comm, SecretKeySpec(ByteArray(16), "AES"), V.T14_RND_A.hexToBytes(), V.T14_RND_B.hexToBytes())
        comm.startEncryptedSession(mode, 0, 0, V.A_TI.hexToBytes())
        ChipCommands.writeData(comm, CommunicationMode.FULL, 2, 0, V.T17_DATA.hexToBytes())
        assertEquals(1, comm.commandCounter)
        fake.assertDone()
    }

    // ---- file CommMode and plain / MAC WriteData ----

    @Test fun table7GetFileSettingsInMacMode() {
        val fake = FakeTransceiver().expect("Table 7 GetFileSettings", V.T7_CMD, V.T7_RESP)
        val comm = communicator(fake)
        comm.startEncryptedSession(FixedSessionMode(comm, ByteArray(16), V.T7_MAC.hexToBytes()), 0, 0, V.T7_TI.hexToBytes())
        // FileOption 40: SDM on, CommMode bits 00 = plain. The response MAC 2A474282E7A47986 is checked.
        assertEquals(CommunicationMode.PLAIN, ChipCommands.fileCommMode(comm, 2))
        assertEquals(1, comm.commandCounter)
        fake.assertDone()
    }

    @Test fun getFileSettingsWithABadResponseMacIsRefused() {
        val bad = V.T7_DATA + "2A474282E7A47987" + "9100"
        val fake = FakeTransceiver().expect("Table 7 GetFileSettings", V.T7_CMD, bad)
        val comm = communicator(fake)
        comm.startEncryptedSession(FixedSessionMode(comm, ByteArray(16), V.T7_MAC.hexToBytes()), 0, 0, V.T7_TI.hexToBytes())
        try {
            ChipCommands.fileCommMode(comm, 2)
            org.junit.Assert.fail("Expected a MAC failure")
        } catch (e: net.bplearning.ntag424.exception.MACValidationException) {
            // expected
        }
    }

    /**
     * The CmdCtr evidence in AN12196 session B: Table 24 (WriteData, CommMode.PLAIN) sits between
     * Table 23 (NonFirst, keeps CmdCtr 1 after Table 21) and Table 25 (ChangeKey at CmdCtr 2). Our
     * plain WriteData must reproduce Table 24 and advance the counter, or Table 25 wouldn't replay.
     */
    @Test fun table24PlainWriteDataAdvancesCmdCtr_soTable25StillReplays() {
        val fake = FakeTransceiver()
            .expect("Table 24 WriteData plain", V.T24_CMD, V.T24_RESP)
            .expect("Table 25 ChangeKey 2", V.T25_CMD, V.T25_RESP)
        val comm = communicator(fake)
        val mode = AESEncryptionMode(comm, SecretKeySpec(ByteArray(16), "AES"), V.T23_RND_A.hexToBytes(), V.T23_RND_B.hexToBytes())
        comm.startEncryptedSession(mode, 0, 1, V.B_TI.hexToBytes())
        ChipCommands.writeData(comm, CommunicationMode.PLAIN, 1, 0x0E, V.T24_DATA.hexToBytes())
        assertEquals(2, comm.commandCounter)
        ChangeKey.run(comm, 2, ByteArray(16), V.T25_NEW_KEY.hexToBytes(), 1)
        fake.assertDone()
    }

    @Test fun macWriteDataIsPlainDataWithACheckedMac() {
        val ref = RefSession(V.A_ENC, V.A_MAC, V.A_TI, 0)
        val data = V.T17_DATA.hexToBytes()
        val fake = FakeTransceiver().expectChecked("WriteData MAC") { apdu ->
            assertEquals("Lc = 7 + 128 + 8", 143, apdu[4].toInt() and 0xFF)
            assertEquals(data.toHex(), ref.verifyCommand(apdu, 0x8D, "02000000800000".hexToBytes(), decrypt = false).toHex())
            ref.respond()
        }
        val comm = communicator(fake)
        val mode = AESEncryptionMode(comm, SecretKeySpec(ByteArray(16), "AES"), V.T14_RND_A.hexToBytes(), V.T14_RND_B.hexToBytes())
        comm.startEncryptedSession(mode, 0, 0, V.A_TI.hexToBytes())
        ChipCommands.writeData(comm, CommunicationMode.MAC, 2, 0, data)
        assertEquals(1, comm.commandCounter)
        fake.assertDone()
    }

    @Test fun iso9797PadAlwaysAddsAByte() {
        assertArrayEquals(("AA" + "80" + "00".repeat(14)).hexToBytes(), ChipCommands.iso9797Pad("AA".hexToBytes()))
        assertEquals(32, ChipCommands.iso9797Pad(ByteArray(16)).size)
        assertEquals(0x80.toByte(), ChipCommands.iso9797Pad(ByteArray(16))[16])
    }

    private fun communicator(fake: FakeTransceiver) = DnaCommunicator().apply { setTransceiver { fake(it) } }

    /** An AES session with given session keys (Table 28 gives keys, not RndA/RndB). */
    private class FixedSessionMode(comm: DnaCommunicator, enc: ByteArray, mac: ByteArray) :
        AESEncryptionMode(comm, SecretKeySpec(ByteArray(16), "AES"), ByteArray(16), ByteArray(16)) {
        init {
            sessionEncryptionKey = SecretKeySpec(enc, "AES")
            sessionMacKey = SecretKeySpec(mac, "AES")
        }
    }
}
