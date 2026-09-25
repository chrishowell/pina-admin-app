package uk.co.mypina.admin.nfc

import android.nfc.tech.IsoDep
import java.io.IOException

/**
 * Why a verify read failed. The message is shown to the person; it carries at most a status word.
 */
class TagReadException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        /** ISOSelectFile of the NDEF application was refused: probably not an NTAG 424 DNA. */
        NOT_NTAG424,
        /** The chip refused ReadData (e.g. file 02's read right isn't free). */
        REFUSED,
        /** NLEN is 0 (a blank chip) or the file doesn't hold a URI record. */
        NO_URL,
    }
}

/**
 * What [TagVerifier.readForIdentify] found. [url] is the tap URL, or null with [noUrl] saying why
 * (blank chip / no URI record, or the ReadData refused). [keyVersions] are keys 0, 1 and 2 as
 * GetKeyVersion reports them, or null if the chip refused GetKeyVersion.
 */
data class IdentifyRead(
    val url: String?,
    val noUrl: TagReadException? = null,
    val keyVersions: List<Int>? = null,
)

/**
 * Reads the tap URL from an NTAG 424 DNA chip for the Verify screen, **without authenticating
 * and without any key**, exactly as a customer's phone would. Free of Android types so it is
 * unit-tested against [FakeTransceiver]-style lambdas; [readUrl] for [IsoDep] is the adapter.
 *
 * Two commands per tap, and exactly one ReadData:
 *  1. ISOSelectFile by DF name (AN12196 Table 22): `00 A4 04 0C 07 D2760000850101 00` → `90 00`.
 *  2. ReadData file 02, CommMode.PLAIN, offset 0, length 256 (the whole NDEF file on an NTAG 424
 *     DNA): `90 AD 00 00 07 02 000000 000100 00` (offset and length 3 bytes little-endian).
 *     `91 00` ends the data; `91 AF` means more follows, fetched with `90 AF 00 00 00`
 *     (a continuation of the same ReadData, not a new read).
 *
 * Why one read of the whole file rather than NLEN first: every plain read of an SDM file
 * increments the chip's SDMReadCtr and produces a fresh e=/c=, so reading NLEN and then the record
 * would cost two counter values and the URL would carry the second. One 256-byte read covers any
 * URL the file can hold; NLEN and the URI record are then parsed from it with [NdefUri].
 *
 * [readForIdentify] (the Identify screen) sends the same two commands, then GetKeyVersion for keys
 * 0, 1 and 2: `90 64 00 00 01 0K 00` → `VV 91 00`, plain and unauthenticated. GetKeyVersion doesn't
 * touch SDMReadCtr, so there is still exactly one counter-consuming read. [readUrl] (Verify) is
 * unchanged.
 */
class TagVerifier(private val transceive: (ByteArray) -> ByteArray) {

    fun readUrl(): String {
        select()
        return parseUrl(readFile())
    }

    /**
     * Select, the one ReadData, then GetKeyVersion 0/1/2. A refused select still throws
     * (NOT_NTAG424); a blank or refused read is kept in [IdentifyRead.noUrl] and the key versions
     * are still read. A refused GetKeyVersion stops there and leaves the versions null.
     */
    fun readForIdentify(): IdentifyRead {
        select()
        var url: String? = null
        var noUrl: TagReadException? = null
        try {
            url = parseUrl(readFile())
        } catch (e: TagReadException) {
            noUrl = e
        }
        return IdentifyRead(url, noUrl, readKeyVersions())
    }

    /** GetKeyVersion for keys 0..2, plain; null as soon as the chip refuses one. */
    private fun readKeyVersions(): List<Int>? {
        val out = ArrayList<Int>(3)
        for (key in 0..2) {
            val r = send(getKeyVersion(key))
            if (sw(r) != 0x9100 || r.size != 3) return null
            out += r[0].toInt() and 0xFF
        }
        return out
    }

    private fun parseUrl(file: ByteArray): String {
        if (file.size < 2 || (file[0].toInt() or file[1].toInt()) == 0) throw noUrl()
        return try {
            NdefUri.parseFile(file)
        } catch (_: IllegalArgumentException) {
            throw noUrl()
        }
    }

    private fun select() {
        val r = send(SELECT_NDEF_APP)
        if (sw(r) != 0x9000) {
            throw TagReadException(
                TagReadException.Kind.NOT_NTAG424,
                "Couldn't select the chip's NDEF application (status ${swHex(r)}). Is this an NTAG 424 DNA?",
            )
        }
    }

    private fun readFile(): ByteArray {
        var r = send(READ_NDEF_FILE)
        var out = r.copyOfRange(0, r.size - 2)
        var frames = 0
        while (sw(r) == 0x91AF) {
            if (++frames > MAX_CONTINUATIONS) throw IOException("Too many continuation frames from the chip")
            r = send(CONTINUE)
            out += r.copyOfRange(0, r.size - 2)
        }
        if (sw(r) != 0x9100) {
            throw TagReadException(TagReadException.Kind.REFUSED, "The chip refused the read (status ${swHex(r)}).")
        }
        return out
    }

    private fun send(apdu: ByteArray): ByteArray {
        val r = transceive(apdu)
        if (r.size < 2) throw IOException("Short response from the chip")
        return r
    }

    private fun noUrl() = TagReadException(TagReadException.Kind.NO_URL, "This chip is blank or has no URL.")

    companion object {
        const val NDEF_FILE = 0x02
        /** The NTAG 424 DNA's NDEF file (file 02) is 256 bytes; read it all in one ReadData. */
        const val READ_LENGTH = 256
        private const val MAX_CONTINUATIONS = 8

        private val DF_NAME = "D2760000850101".hexToBytes()

        val SELECT_NDEF_APP: ByteArray =
            byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x0C, DF_NAME.size.toByte(), *DF_NAME, 0x00)

        val READ_NDEF_FILE: ByteArray = byteArrayOf(
            0x90.toByte(), 0xAD.toByte(), 0x00, 0x00, 0x07,
            NDEF_FILE.toByte(),
            0x00, 0x00, 0x00, // offset 0
            READ_LENGTH.toByte(), (READ_LENGTH shr 8).toByte(), (READ_LENGTH shr 16).toByte(),
            0x00,
        )

        val CONTINUE: ByteArray = byteArrayOf(0x90.toByte(), 0xAF.toByte(), 0x00, 0x00, 0x00)

        /** GetKeyVersion, CommMode.PLAIN: `90 64 00 00 01 KeyNo 00`. */
        fun getKeyVersion(key: Int): ByteArray = byteArrayOf(0x90.toByte(), 0x64, 0x00, 0x00, 0x01, key.toByte(), 0x00)

        /** The Android adapter: [iso] connected by the caller, who also closes it. */
        fun readUrl(iso: IsoDep): String = TagVerifier { iso.transceive(it) }.readUrl()

        /** The Identify screen's read over [iso] (connected and closed by the caller). */
        fun readForIdentify(iso: IsoDep): IdentifyRead = TagVerifier { iso.transceive(it) }.readForIdentify()

        private fun sw(r: ByteArray) = ((r[r.size - 2].toInt() and 0xFF) shl 8) or (r[r.size - 1].toInt() and 0xFF)
        private fun swHex(r: ByteArray) = "%04X".format(sw(r))
    }
}
