package uk.co.mypina.admin.nfc

import net.bplearning.ntag424.CommunicationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import uk.co.mypina.admin.api.SdmSettings

class SdmFileSettingsTest {
    private val pina = SdmSettings("40", "00E0", "C1", "FF12", 0x20, 0x43, 0x43)

    @Test fun pinaSettingsEncodeToGuideBytes() {
        // guide §3: 40 00E0 C1 FF12 <PICCDataOffset> <SDMMACInputOffset> <SDMMACOffset>, 3 bytes LE each.
        assertEquals("4000E0C1FF12200000430000430000", SdmFileSettings.dataField(pina).toHex())
    }

    @Test fun libraryEncoderAgreesWithServerHex() {
        assertEquals(SdmFileSettings.rawDataField(pina).toHex(), SdmFileSettings.toLibrary(pina).encodeToData().toHex())
        val lib = SdmFileSettings.toLibrary(pina)
        assertEquals(CommunicationMode.PLAIN, lib.commMode)
        assertEquals(0xE, lib.readPerm)
        assertEquals(0, lib.writePerm)
        assertEquals(0, lib.readWritePerm)
        assertEquals(0, lib.changePerm)
        assertEquals(1, lib.sdmSettings.sdmMetaReadPerm)
        assertEquals(2, lib.sdmSettings.sdmFileReadPerm)
        assertEquals(0xF, lib.sdmSettings.sdmReadCounterRetrievalPerm)
    }

    @Test fun offsetsAreThreeBytesLittleEndianInDatasheetOrder() {
        val s = pina.copy(piccDataOffset = 0x010203, sdmMacInputOffset = 0x0A0B0C, sdmMacOffset = 0x112233)
        assertEquals("4000E0C1FF12" + "030201" + "0C0B0A" + "332211", SdmFileSettings.dataField(s).toHex())
    }

    @Test fun an12196Table18SettingsEncodeToTable18Data() {
        val t18 = SdmSettings("40", "00E0", "C1", "F121", 0x20, 0x43, 0x43)
        assertEquals(An12196.T18_DATA, SdmFileSettings.dataField(t18).toHex())
    }

    @Test fun mismatchBetweenServerHexAndLibraryIsRefused() {
        // SDMCtrRet set while the read counter is off: the library forces F, so the bytes differ.
        val odd = pina.copy(sdmOptions = "81", sdmAccessRights = "F112")
        assertThrows(SdmFileSettings.Unsupported::class.java) { SdmFileSettings.dataField(odd) }
    }

    @Test fun unsupportedLayoutsAreRefused() {
        for (bad in listOf(
            pina.copy(fileOption = "00"),          // SDM off
            pina.copy(sdmOptions = "D1"),          // encrypted file data
            pina.copy(sdmOptions = "E1"),          // read counter limit
            pina.copy(sdmAccessRights = "FFE2"),   // plain PICC data (UID/ctr offsets instead)
            pina.copy(sdmAccessRights = "FF1F"),   // no SDM MAC
            pina.copy(accessRights = "E0"),        // wrong length
            pina.copy(sdmMacOffset = -1),
        )) {
            assertThrows(bad.toString(), IllegalArgumentException::class.java) { SdmFileSettings.dataField(bad) }
        }
    }
}
