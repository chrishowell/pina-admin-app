package uk.co.mypina.admin.nfc

import net.bplearning.ntag424.CommunicationMode
import net.bplearning.ntag424.command.FileSettings
import uk.co.mypina.admin.api.SdmSettings

/**
 * Turns the server's SDM settings into the ChangeFileSettings data field for file 02.
 *
 * The server sends the header as hex (`fileOption`, `accessRights`, `sdmOptions`,
 * `sdmAccessRights`) plus three offsets. We build the data field twice, independently:
 *  - [rawDataField]: the server's bytes concatenated with the offsets as 3-byte little-endian, in
 *    the datasheet order PICCDataOffset, SDMMACInputOffset, SDMMACOffset (guide §3);
 *  - [toLibrary]: the library's [FileSettings] decoded from the same fields, then
 *    [FileSettings.encodeToData].
 * [dataField] insists they are identical, so a disagreement between our reading of the server's
 * fields and the library's encoder stops the write instead of sending the chip something else.
 *
 * Only the layout Piña uses is supported: SDM on, encrypted PICC data (SDMMetaRead a key, 0..4),
 * SDM MAC on (SDMFileRead not F), no encrypted file data, no read-counter limit. Anything else is
 * refused, because the raw layout above would then be wrong.
 */
object SdmFileSettings {

    class Unsupported(message: String) : IllegalArgumentException(message)

    fun dataField(sdm: SdmSettings): ByteArray {
        val raw = rawDataField(sdm)
        val lib = toLibrary(sdm).encodeToData()
        if (!raw.contentEquals(lib)) {
            throw Unsupported("The SDM settings from the server don't encode as expected (${raw.toHex()} vs ${lib.toHex()}).")
        }
        return raw
    }

    fun rawDataField(sdm: SdmSettings): ByteArray {
        validate(sdm)
        return byteArrayOf(*one(sdm.fileOption), *two(sdm.accessRights), *one(sdm.sdmOptions), *two(sdm.sdmAccessRights)) +
            le3(sdm.piccDataOffset) + le3(sdm.sdmMacInputOffset) + le3(sdm.sdmMacOffset)
    }

    fun toLibrary(sdm: SdmSettings): FileSettings {
        validate(sdm)
        val fileOption = one(sdm.fileOption)[0].u()
        val ar = two(sdm.accessRights)
        val opts = one(sdm.sdmOptions)[0].u()
        val sar = two(sdm.sdmAccessRights)

        return FileSettings().apply {
            commMode = when (fileOption and 0x03) {
                0 -> CommunicationMode.PLAIN
                1 -> CommunicationMode.MAC
                2 -> CommunicationMode.PLAIN_ALT
                else -> CommunicationMode.FULL
            }
            // AccessRights byte 0 = ReadWrite | Change, byte 1 = Read | Write (datasheet, AN12196 Table 18).
            readWritePerm = ar[0].u() shr 4
            changePerm = ar[0].u() and 0x0F
            readPerm = ar[1].u() shr 4
            writePerm = ar[1].u() and 0x0F
            sdmSettings.apply {
                sdmEnabled = fileOption and 0x40 != 0
                sdmOptionUid = opts and 0x80 != 0
                sdmOptionReadCounter = opts and 0x40 != 0
                sdmOptionReadCounterLimit = opts and 0x20 != 0
                sdmOptionEncryptFileData = opts and 0x10 != 0
                sdmOptionUseAscii = opts and 0x01 != 0
                // SDMAccessRights byte 0 = RFU | SDMCtrRet, byte 1 = SDMMetaRead | SDMFileRead.
                sdmReadCounterRetrievalPerm = sar[0].u() and 0x0F
                sdmMetaReadPerm = sar[1].u() shr 4
                sdmFileReadPerm = sar[1].u() and 0x0F
                sdmPiccDataOffset = sdm.piccDataOffset
                sdmMacInputOffset = sdm.sdmMacInputOffset
                sdmMacOffset = sdm.sdmMacOffset
            }
        }
    }

    private fun validate(sdm: SdmSettings) {
        val fileOption = one(sdm.fileOption)[0].u()
        val opts = one(sdm.sdmOptions)[0].u()
        val sar = two(sdm.sdmAccessRights)
        val meta = sar[1].u() shr 4
        val file = sar[1].u() and 0x0F
        if (fileOption and 0x40 == 0) throw Unsupported("The server's file settings don't switch SDM on.")
        if (fileOption and 0xBC != 0) throw Unsupported("Unsupported FileOption bits.")
        if (opts and 0x30 != 0) throw Unsupported("Encrypted file data or a read-counter limit isn't supported.")
        if (opts and 0x0E != 0) throw Unsupported("Unsupported SDMOptions bits.")
        if (meta !in 0..4) throw Unsupported("Only encrypted PICC data (SDMMetaRead 0..4) is supported.")
        if (file == 0x0F) throw Unsupported("SDM MAC must be on (SDMFileRead not F).")
        for (o in listOf(sdm.piccDataOffset, sdm.sdmMacInputOffset, sdm.sdmMacOffset)) {
            if (o !in 0..0xFFFFFF) throw Unsupported("SDM offset out of range.")
        }
    }

    private fun one(hex: String): ByteArray = hex.hexToBytes().also { if (it.size != 1) throw Unsupported("Expected 1 byte, got \"$hex\".") }
    private fun two(hex: String): ByteArray = hex.hexToBytes().also { if (it.size != 2) throw Unsupported("Expected 2 bytes, got \"$hex\".") }
    private fun le3(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
    private fun Byte.u() = toInt() and 0xFF
}
