package uk.co.mypina.admin.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NdefUriTest {
    @Test fun parsesAn12196Table15File() {
        assertEquals(An12196.NDEF_URL, NdefUri.parseFile(An12196.NDEF_53.hexToBytes()))
    }

    @Test fun ignoresBytesAfterNlen() {
        // Table 17 writes the file padded with zeros to 128 bytes; reading 128 back must still work.
        assertEquals(An12196.NDEF_URL, NdefUri.parseFile(An12196.T17_DATA.hexToBytes()))
    }

    @Test fun parsesPinaShapedUrlWithMirroredValues() {
        val url = "https://mypina.co.uk/t/ABC1234?e=EF963FF7828658A599F3041510671E88&c=94EED9EE65337086"
        assertEquals(url, NdefUri.parseFile(file(0x04, url.removePrefix("https://"))))
    }

    @Test fun parsesLongRecordAndOtherPrefixes() {
        val rest = "example.com/x"
        val payload = byteArrayOf(0x03) + rest.toByteArray()
        val rec = byteArrayOf(0xC1.toByte(), 0x01, 0, 0, 0, payload.size.toByte(), 0x55) + payload
        assertEquals("http://example.com/x", NdefUri.parseFile(byteArrayOf(0, rec.size.toByte()) + rec))
    }

    @Test fun rejectsBadFiles() {
        assertThrows(IllegalArgumentException::class.java) { NdefUri.parseFile(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { NdefUri.parseFile("0000".hexToBytes()) }
        assertThrows(IllegalArgumentException::class.java) { NdefUri.parseFile("0051D101".hexToBytes()) } // NLEN past end
        assertThrows(IllegalArgumentException::class.java) { NdefUri.parseFile("0005D101015400".hexToBytes()) } // type T
        assertThrows(IllegalArgumentException::class.java) { NdefUri.parseFile("0005D101015523".hexToBytes()) } // unknown prefix
        assertThrows(IllegalArgumentException::class.java) { NdefUri.parseFile("0005D101055504".hexToBytes()) } // payload truncated
    }

    private fun file(prefix: Int, rest: String): ByteArray {
        val payload = byteArrayOf(prefix.toByte()) + rest.toByteArray()
        val rec = byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), 0x55) + payload
        return byteArrayOf((rec.size shr 8).toByte(), rec.size.toByte()) + rec
    }
}
