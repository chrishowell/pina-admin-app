package uk.co.mypina.admin.nfc

/**
 * Reads the URL back out of an NTAG 424 NDEF file (file 02): NLEN (2 bytes, big-endian) followed
 * by one NFC Forum URI record, as the server's `ndefFileHex` lays it out
 * (`NLEN || D1 01 <len> 55 <prefix> <rest of URL>`). Also accepts a long record (SR clear,
 * 4-byte payload length). Throws [IllegalArgumentException] with a short, key-free message.
 */
object NdefUri {
    /** NFC Forum URI RTD abbreviation codes that a Piña URL could use. */
    private val PREFIXES = mapOf(
        0x00 to "",
        0x01 to "http://www.",
        0x02 to "https://www.",
        0x03 to "http://",
        0x04 to "https://",
    )

    fun parseFile(file: ByteArray): String {
        require(file.size >= 2) { "NDEF file too short" }
        val nlen = (file[0].u() shl 8) or file[1].u()
        require(nlen > 0) { "NDEF file is empty" }
        require(2 + nlen <= file.size) { "NDEF length runs past the data read" }
        return parseRecord(file.copyOfRange(2, 2 + nlen))
    }

    fun parseRecord(msg: ByteArray): String {
        var i = 0
        fun next(): Int {
            require(i < msg.size) { "NDEF record truncated" }
            return msg[i++].u()
        }
        val header = next()
        val tnf = header and 0x07
        val shortRecord = header and 0x10 != 0
        val hasId = header and 0x08 != 0
        require(tnf == 0x01) { "NDEF record isn't a well-known type" }
        val typeLen = next()
        val payloadLen = if (shortRecord) next() else (next() shl 24) or (next() shl 16) or (next() shl 8) or next()
        val idLen = if (hasId) next() else 0
        require(typeLen == 1) { "NDEF record type isn't U" }
        require(next() == 'U'.code) { "NDEF record type isn't U" }
        i += idLen
        require(payloadLen >= 1 && i + payloadLen <= msg.size) { "NDEF payload truncated" }
        val prefix = PREFIXES[msg[i].u()] ?: throw IllegalArgumentException("Unsupported URI prefix")
        val rest = String(msg, i + 1, payloadLen - 1, Charsets.UTF_8)
        return prefix + rest
    }

    private fun Byte.u() = toInt() and 0xFF
}
