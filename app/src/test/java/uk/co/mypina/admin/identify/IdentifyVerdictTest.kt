package uk.co.mypina.admin.identify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import uk.co.mypina.admin.api.ApiException
import uk.co.mypina.admin.api.IdentifiedChip
import uk.co.mypina.admin.api.IdentifyResponse
import uk.co.mypina.admin.api.TagSummary
import uk.co.mypina.admin.nfc.IdentifyRead
import uk.co.mypina.admin.nfc.TagReadException
import java.io.IOException

class IdentifyVerdictTest {
    private val uid = "04958CAA5C5E80"
    private val url = "https://mypina.co.uk/t/ABC1234?e=EF963FF7828658A599F3041510671E88&c=94EED9EE65337086"

    private fun tag(id: String = "t1", code: String = "ABC1234", shop: String = "The Anchor") = TagSummary(
        id = id, code = code, label = "Table 4", shopId = "s1", shopName = shop, shopStatus = "approved",
        active = true, authMode = "sun", encodedAt = "2026-09-20T10:00:00Z", lastCounter = 11, keyVersion = 1,
    )

    private fun chip(
        signature: String = "ok",
        tagCode: String? = "ABC1234",
        urlTag: TagSummary? = tag(),
        counter: Long? = 12,
        fresh: Boolean? = true,
        uidMatches: Boolean? = true,
    ) = IdentifiedChip(url, tagCode, urlTag, signature, counter, fresh, uidMatches)

    private fun verdict(
        bound: TagSummary?,
        chip: IdentifiedChip?,
        read: ChipRead = chip?.let { ChipRead.Url(url) } ?: ChipRead.Blank,
        keys: List<Int>? = null,
    ) = identifyVerdict(IdentifyResponse(uid, bound, chip), read, keys)

    private val resetTitle =
        "Reset chip: factory keys, still carrying the URL for tag ABC1234 at The Anchor. Write it from the server you want to own it."

    // ---- no URL --------------------------------------------------------------------------------

    @Test fun blank_unbound() {
        val v = verdict(null, null)
        assertEquals(Tone.NEUTRAL, v.tone)
        assertEquals("Blank chip, not bound to any tag", v.title)
        assertEquals(emptyList<String>(), v.notes)
        assertNull(v.tag)
    }

    @Test fun blank_butBound() {
        val bound = tag()
        val v = verdict(bound, null)
        assertEquals(Tone.WARN, v.tone)
        assertEquals("Blank chip, but its UID is bound to tag ABC1234 at The Anchor", v.title)
        assertEquals(listOf("The tag's row expects this chip: write it from the tag's page."), v.notes)
        assertSame(bound, v.tag)
    }

    @Test fun selectRefused_unbound_saysNotNtag424() {
        val v = verdict(null, null, ChipRead.NotNtag424("select refused"))
        assertEquals("Not bound to any tag", v.title)
        assertEquals(listOf("Not an NTAG 424 DNA (select refused)"), v.notes)
    }

    @Test fun selectRefused_bound() {
        val v = verdict(tag(), null, ChipRead.NotNtag424("select refused"))
        assertEquals("Its UID is bound to tag ABC1234 at The Anchor", v.title)
        assertEquals(listOf("Not an NTAG 424 DNA (select refused)"), v.notes)
    }

    @Test fun readRefused_isANote() {
        val v = verdict(null, null, ChipRead.Refused("The chip refused the read (status 919D)."))
        assertEquals(listOf("The chip refused the read (status 919D)."), v.notes)
    }

    // ---- signature ok --------------------------------------------------------------------------

    @Test fun ok_boundToTheSameTag_isGood() {
        val v = verdict(tag(), chip())
        assertEquals(Tone.GOOD, v.tone)
        assertEquals("Tag ABC1234 at The Anchor, signature OK", v.title)
        assertEquals(emptyList<String>(), v.notes)
        assertEquals("ABC1234", v.tag?.code)
        assertEquals("12, fresh (server counter 11)", v.counter)
    }

    @Test fun ok_counterAlreadySeen() {
        val v = verdict(tag(), chip(counter = 7, fresh = false))
        assertEquals(Tone.GOOD, v.tone)
        assertEquals("7, already seen (server counter 11)", v.counter)
    }

    @Test fun ok_uidUnbound() {
        val v = verdict(null, chip())
        assertEquals(Tone.WARN, v.tone)
        assertEquals("Written for tag ABC1234, but the UID is bound to none", v.title)
    }

    @Test fun ok_uidBoundToAnotherRow() {
        val v = verdict(tag(id = "t2", code = "XYZ9", shop = "The Crown"), chip())
        assertEquals(Tone.WARN, v.tone)
        assertEquals("Written for tag ABC1234, but the UID is bound to tag XYZ9 at The Crown", v.title)
        assertEquals("ABC1234", v.tag?.code)
    }

    @Test fun ok_uidInUrlIsAnotherChip() {
        val v = verdict(tag(), chip(uidMatches = false))
        assertEquals(Tone.BAD, v.tone)
        assertEquals("The URL was written for a different chip (UID in URL ≠ this chip)", v.title)
        assertEquals(listOf("Signature OK for tag ABC1234"), v.notes)
    }

    // ---- other signature results ---------------------------------------------------------------

    @Test fun badCmac_stillShowsTheUrlTag() {
        val v = verdict(null, chip(signature = "bad_cmac", counter = null, fresh = null, uidMatches = null))
        assertEquals(Tone.BAD, v.tone)
        assertEquals("Signature invalid: written by another server or tampered", v.title)
        assertEquals(listOf("The URL names tag ABC1234"), v.notes)
        assertEquals("ABC1234", v.tag?.code)
        assertNull(v.counter)
    }

    @Test fun badCmac_boundElsewhere_saysSo() {
        val v = verdict(tag(id = "t2", code = "XYZ9", shop = "The Crown"), chip(signature = "bad_cmac"))
        assertEquals(listOf("The URL names tag ABC1234", "The UID is bound to tag XYZ9 at The Crown"), v.notes)
    }

    @Test fun unknownTag() {
        val v = verdict(null, chip(signature = "unknown_tag", tagCode = "ZZZ999", urlTag = null))
        assertEquals(Tone.BAD, v.tone)
        assertEquals("URL points at tag ZZZ999, which doesn't exist on this server", v.title)
        assertNull(v.tag)
    }

    @Test fun unsigned_isADemoTag() {
        val v = verdict(null, chip(signature = "unsigned", tagCode = "DEMO1", urlTag = tag(code = "DEMO1")))
        assertEquals(Tone.NEUTRAL, v.tone)
        assertEquals("Demo tag DEMO1 (no signature)", v.title)
        assertEquals("DEMO1", v.tag?.code)
    }

    @Test fun malformed() {
        val v = verdict(null, chip(signature = "malformed", tagCode = null, urlTag = null))
        assertEquals(Tone.WARN, v.tone)
        assertEquals("Has a URL, but not a Piña tap URL", v.title)
    }

    @Test fun keysMissing() {
        val v = verdict(null, chip(signature = "keys_missing"))
        assertEquals(Tone.WARN, v.tone)
        assertEquals("Server has no NFC keys; can't check the signature", v.title)
    }

    // ---- key versions --------------------------------------------------------------------------

    @Test fun keyState_fromVersions() {
        assertEquals(KeyState.Factory, keyState(listOf(0, 0, 0)))
        assertEquals(KeyState.Pina(1), keyState(listOf(1, 1, 1)))
        assertEquals(KeyState.Mixed(listOf(0, 1, 1)), keyState(listOf(0, 1, 1)))
        assertNull(keyState(null))
        assertEquals("factory", keysLine(KeyState.Factory))
        assertEquals("Piña (version 3)", keysLine(KeyState.Pina(3)))
        assertEquals("mixed (0/1/1)", keysLine(KeyState.Mixed(listOf(0, 1, 1))))
        assertEquals("unknown", keysLine(null))
    }

    @Test fun keysLine_onTheVerdict() {
        assertEquals("Piña (version 1)", verdict(tag(), chip(), keys = listOf(1, 1, 1)).keys)
        assertEquals("factory", verdict(null, null, keys = listOf(0, 0, 0)).keys)
        assertEquals("mixed (0/1/1)", verdict(null, null, keys = listOf(0, 1, 1)).keys)
        assertEquals("unknown", verdict(tag(), chip()).keys)
        // Not an NTAG 424 DNA: no keys to ask about, so no Keys line.
        assertNull(verdict(null, null, ChipRead.NotNtag424("select refused")).keys)
    }

    @Test fun factorySignature_isAResetChip() {
        val v = verdict(null, chip(signature = "factory", counter = null, fresh = null, uidMatches = null), keys = listOf(0, 0, 0))
        assertEquals(Tone.WARN, v.tone)
        assertEquals(resetTitle, v.title)
        assertEquals(emptyList<String>(), v.notes)
        assertEquals("ABC1234", v.tag?.code)
        assertEquals("factory", v.keys)
    }

    @Test fun factorySignature_keysUnknown_stillAResetChip() {
        val v = verdict(null, chip(signature = "factory"))
        assertEquals(Tone.WARN, v.tone)
        assertEquals(resetTitle, v.title)
        assertEquals("unknown", v.keys)
    }

    @Test fun factorySignature_unknownRow_namesTheCode() {
        val v = verdict(null, chip(signature = "factory", urlTag = null))
        assertEquals(
            "Reset chip: factory keys, still carrying the URL for tag ABC1234. Write it from the server you want to own it.",
            v.title,
        )
        assertNull(v.tag)
    }

    @Test fun factorySignature_boundElsewhere_saysSo() {
        val v = verdict(tag(id = "t2", code = "XYZ9", shop = "The Crown"), chip(signature = "factory"), keys = listOf(0, 0, 0))
        assertEquals(resetTitle, v.title)
        assertEquals(listOf("The UID is bound to tag XYZ9 at The Crown"), v.notes)
    }

    @Test fun badCmac_factoryKeys_isAResetChip() {
        val v = verdict(null, chip(signature = "bad_cmac", counter = null, fresh = null, uidMatches = null), keys = listOf(0, 0, 0))
        assertEquals(Tone.WARN, v.tone)
        assertEquals(resetTitle, v.title)
        assertEquals("ABC1234", v.tag?.code)
        assertEquals("factory", v.keys)
    }

    @Test fun badCmac_pinaKeys_isAnotherServer() {
        val v = verdict(null, chip(signature = "bad_cmac", counter = null, fresh = null, uidMatches = null), keys = listOf(2, 2, 2))
        assertEquals(Tone.BAD, v.tone)
        assertEquals("Written by another server (keys at version 2, signature doesn't match this server)", v.title)
        assertEquals(listOf("The URL names tag ABC1234"), v.notes)
        assertEquals("Piña (version 2)", v.keys)
    }

    @Test fun badCmac_mixedKeys_isTheGenericMessage() {
        val v = verdict(null, chip(signature = "bad_cmac"), keys = listOf(0, 1, 1))
        assertEquals(Tone.BAD, v.tone)
        assertEquals("Signature invalid: written by another server or tampered", v.title)
        assertEquals("mixed (0/1/1)", v.keys)
    }

    @Test fun badCmac_unknownKeys_isTheGenericMessage() {
        val v = verdict(null, chip(signature = "bad_cmac"), keys = null)
        assertEquals("Signature invalid: written by another server or tampered", v.title)
        assertEquals("unknown", v.keys)
    }

    // ---- read and UID helpers ------------------------------------------------------------------

    @Test fun readChip_mapsTagReadExceptions() {
        assertEquals(ChipRead.Url(url), readChip { url })
        assertEquals(ChipRead.Blank, readChip { throw TagReadException(TagReadException.Kind.NO_URL, "x") })
        assertEquals(
            ChipRead.NotNtag424("select refused"),
            readChip { throw TagReadException(TagReadException.Kind.NOT_NTAG424, "x") },
        )
        assertEquals(
            ChipRead.Refused("The chip refused the read (status 919D)."),
            readChip { throw TagReadException(TagReadException.Kind.REFUSED, "The chip refused the read (status 919D).") },
        )
        assertThrows(IOException::class.java) { readChip { throw IOException("lost") } }
        assertNull(ChipRead.Blank.url)
    }

    @Test fun scanChip_foldsTheIdentifyRead() {
        assertEquals(ChipScan(ChipRead.Url(url), listOf(1, 1, 1)), scanChip { IdentifyRead(url, null, listOf(1, 1, 1)) })
        assertEquals(
            ChipScan(ChipRead.Blank, listOf(0, 0, 0)),
            scanChip { IdentifyRead(null, TagReadException(TagReadException.Kind.NO_URL, "x"), listOf(0, 0, 0)) },
        )
        assertEquals(
            ChipScan(ChipRead.Refused("The chip refused the read (status 919D)."), null),
            scanChip { IdentifyRead(null, TagReadException(TagReadException.Kind.REFUSED, "The chip refused the read (status 919D)."), null) },
        )
        assertEquals(
            ChipScan(ChipRead.NotNtag424("select refused"), null),
            scanChip { throw TagReadException(TagReadException.Kind.NOT_NTAG424, "x") },
        )
        assertThrows(IOException::class.java) { scanChip { throw IOException("lost") } }
    }

    @Test fun uid_isSevenBytesNotRandomId() {
        assertEquals("04958CAA5C5E80", uidFromTagId(byteArrayOf(0x04, 0x95.toByte(), 0x8C.toByte(), 0xAA.toByte(), 0x5C, 0x5E, 0x80.toByte())))
        assertNull(uidFromTagId(byteArrayOf(0x08, 1, 2, 3)))
        assertNull(uidFromTagId(byteArrayOf(0x08, 1, 2, 3, 4, 5, 6)))
        assertNull(uidFromTagId(byteArrayOf(0x04, 1, 2, 3)))
        assertNull(uidFromTagId(null))
    }

    @Test fun errorMessages() {
        assertEquals("Sign in on the website first.", identifyErrorMessage(ApiException.NotSignedIn()))
        assertEquals("The server refused this chip's UID (malformed).", identifyErrorMessage(ApiException.Malformed()))
        assertEquals("Lost contact with the chip. Hold it again.", identifyErrorMessage(IOException()))
    }
}
