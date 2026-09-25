package uk.co.mypina.admin.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import uk.co.mypina.admin.nfc.An12196 as V

/**
 * The Verify screen's keyless read: select by DF name (AN12196 Table 22), then exactly one plain
 * ReadData of all 256 bytes of file 02, never an authentication.
 */
class TagVerifierTest {
    private val readAll = "90AD0000070200000000010000" // file 02, offset 000000, length 000100 (256, LE)
    private val continuation = "90AF000000"

    private val eMirror = "EF963FF7828658A599F3041510671E88"
    private val cMirror = "94EED9EE65337086"

    /** AN12196's NDEF file with the chip's mirrored e= and c= filled in, as the whole 256-byte file. */
    private fun mirroredFile(): ByteArray {
        val f = ByteArray(256)
        V.T17_DATA.hexToBytes().copyInto(f)
        eMirror.toByteArray().copyInto(f, 0x20)
        cMirror.toByteArray().copyInto(f, 0x43)
        return f
    }

    private val expectedUrl = V.NDEF_URL
        .replace("e=" + "0".repeat(32), "e=$eMirror")
        .replace("c=" + "0".repeat(16), "c=$cMirror")

    private fun FakeTransceiver.select() = expect("Table 22 select", V.T22_SELECT, V.T22_RESP)

    private fun readUrl(fake: FakeTransceiver) = TagVerifier(fake).readUrl()

    @Test fun apdus_areExactlySelectThenOneReadData() {
        assertEquals("00A4040C07D276000085010100", TagVerifier.SELECT_NDEF_APP.toHex())
        assertEquals(readAll, TagVerifier.READ_NDEF_FILE.toHex())
        assertEquals(continuation, TagVerifier.CONTINUE.toHex())
    }

    @Test fun readsTheMirroredUrl_withOneReadData() {
        val fake = FakeTransceiver()
            .select()
            .expect("ReadData file 02, 256 bytes", readAll, mirroredFile().toHex() + "9100")

        assertEquals(expectedUrl, readUrl(fake))
        fake.assertDone()
        assertEquals(listOf(V.T22_SELECT, readAll), fake.sent)
    }

    @Test fun followsContinuationFrames() {
        val f = mirroredFile()
        val fake = FakeTransceiver()
            .select()
            .expect("ReadData, first frame", readAll, f.copyOfRange(0, 0x3B).toHex() + "91AF")
            .expect("continuation 1", continuation, f.copyOfRange(0x3B, 0x80).toHex() + "91AF")
            .expect("continuation 2", continuation, f.copyOfRange(0x80, 256).toHex() + "9100")

        assertEquals(expectedUrl, readUrl(fake))
        fake.assertDone()
        assertEquals("one ReadData only", 1, fake.sent.count { it.startsWith("90AD") })
    }

    @Test fun readsAPinaShapedFile() {
        val url = "https://mypina.co.uk/t/ABC1234?e=$eMirror&c=$cMirror"
        val payload = byteArrayOf(0x04) + url.removePrefix("https://").toByteArray()
        val rec = byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), 0x55) + payload
        val file = (byteArrayOf(0, rec.size.toByte()) + rec).copyOf(256)
        val fake = FakeTransceiver().select().expect("ReadData", readAll, file.toHex() + "9100")
        assertEquals(url, readUrl(fake))
    }

    @Test fun blankChip_allZeros() {
        val fake = FakeTransceiver().select().expect("ReadData", readAll, "00".repeat(256) + "9100")
        val e = assertThrows(TagReadException::class.java) { readUrl(fake) }
        assertEquals(TagReadException.Kind.NO_URL, e.kind)
        assertEquals("This chip is blank or has no URL.", e.message)
        fake.assertDone()
    }

    @Test fun blankChip_nlenZeroWithLeftoverBytes() {
        // NLEN 0 means an empty NDEF message whatever follows it.
        val file = mirroredFile().also { it[0] = 0; it[1] = 0 }
        val fake = FakeTransceiver().select().expect("ReadData", readAll, file.toHex() + "9100")
        assertEquals(TagReadException.Kind.NO_URL, assertThrows(TagReadException::class.java) { readUrl(fake) }.kind)
    }

    @Test fun noData_isBlank() {
        val fake = FakeTransceiver().select().expect("ReadData", readAll, "9100")
        assertEquals(TagReadException.Kind.NO_URL, assertThrows(TagReadException::class.java) { readUrl(fake) }.kind)
    }

    @Test fun nonUriRecord_hasNoUrl() {
        // A text record ("T"), not a URI.
        val file = "0007D1010354026568".hexToBytes().copyOf(256)
        val fake = FakeTransceiver().select().expect("ReadData", readAll, file.toHex() + "9100")
        val e = assertThrows(TagReadException::class.java) { readUrl(fake) }
        assertEquals(TagReadException.Kind.NO_URL, e.kind)
    }

    @Test fun selectRefused_stopsBeforeReading() {
        val fake = FakeTransceiver().expect("select", V.T22_SELECT, "6A82")
        val e = assertThrows(TagReadException::class.java) { readUrl(fake) }
        assertEquals(TagReadException.Kind.NOT_NTAG424, e.kind)
        assertEquals(true, e.message!!.contains("6A82"))
        fake.assertDone()
    }

    @Test fun readRefused_namesTheStatus() {
        val fake = FakeTransceiver().select().expect("ReadData", readAll, "919D")
        val e = assertThrows(TagReadException::class.java) { readUrl(fake) }
        assertEquals(TagReadException.Kind.REFUSED, e.kind)
        assertEquals("The chip refused the read (status 919D).", e.message)
    }

    @Test fun shortResponse_isAnIoError() {
        val fake = FakeTransceiver().select().expect("ReadData", readAll, "91")
        assertThrows(java.io.IOException::class.java) { readUrl(fake) }
    }
}
