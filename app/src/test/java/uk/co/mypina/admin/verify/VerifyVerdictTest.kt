package uk.co.mypina.admin.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.mypina.admin.api.ApiException
import uk.co.mypina.admin.api.VerifiedTag
import uk.co.mypina.admin.api.VerifyResponse
import uk.co.mypina.admin.nfc.TagReadException

class VerifyVerdictTest {
    private fun response(
        ok: Boolean = true,
        fresh: Boolean = true,
        uidMatches: Boolean = true,
        tagMatches: Boolean = true,
    ) = VerifyResponse(
        ok = ok,
        tag = VerifiedTag(id = "t1", code = "ABC1234", label = "Table 4", shopName = "The Anchor", encodedAt = "2026-09-20T10:00:00Z"),
        uid = "04958CAA5C5E80",
        counter = 7,
        lastCounter = 9,
        fresh = fresh,
        uidMatches = uidMatches,
        tagMatches = tagMatches,
    )

    @Test fun allGood_isTagVerified() {
        val v = verdictFor(response())
        assertTrue(v.verified)
        assertEquals("Tag verified", v.title)
        assertTrue(v.reasons.isEmpty())
    }

    @Test fun eachProblem_isListed() {
        val v = verdictFor(response(fresh = false, uidMatches = false, tagMatches = false))
        assertFalse(v.verified)
        assertEquals("Verified, but…", v.title)
        assertEquals(
            listOf(
                "counter 7 was already seen, server has 9",
                "chip UID 04958CAA5C5E80 isn't the one bound to this tag",
                "this chip is tag ABC1234, not the one you opened",
            ),
            v.reasons,
        )
    }

    @Test fun okFalse_isNotAPass() {
        assertFalse(verdictFor(response(ok = false)).verified)
    }

    @Test fun tagCode_fromUrl() {
        assertEquals("ABC1234", tagCodeFromUrl("https://mypina.co.uk/t/ABC1234?e=00&c=00"))
        assertNull(tagCodeFromUrl("https://example.com/x"))
    }

    @Test fun errorMessages() {
        val url = "https://mypina.co.uk/t/ZZZ999?e=EF96&c=94EE"
        assertEquals(
            "The signature didn't verify: this chip wasn't written with Piña's keys.",
            verifyErrorMessage(ApiException.BadCmac(), url),
        )
        assertEquals("No tag has the code in this chip's URL (ZZZ999).", verifyErrorMessage(ApiException.UnknownTag(), url))
        assertEquals(
            "This chip is blank or has no URL.",
            verifyErrorMessage(TagReadException(TagReadException.Kind.NO_URL, "This chip is blank or has no URL."), null),
        )
        assertEquals("Sign in on the website first.", verifyErrorMessage(ApiException.NotSignedIn(), url))
    }
}
