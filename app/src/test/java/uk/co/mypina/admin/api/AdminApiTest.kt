package uk.co.mypina.admin.api

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Maps every documented response from the admin routes (see [ApiException] and [ErrorBody]) to
 * the right [ApiException] subtype, against a real HTTP layer (MockWebServer) rather than a fake
 * one, so the request/response wiring in [AdminApi.post] is exercised too.
 */
class AdminApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AdminApi

    @Before fun start() {
        server = MockWebServer()
        server.start()
        api = AdminApi(
            baseUrl = server.url("/").toString(),
            cookieProvider = { null },
            client = AdminApi.defaultClient(),
        )
    }

    @After fun stop() {
        server.shutdown()
    }

    private fun enqueue(code: Int, body: String? = null) {
        val response = MockResponse().setResponseCode(code)
        if (body != null) response.setBody(body).addHeader("Content-Type", "application/json")
        if (code in 300..399) response.addHeader("Location", "https://mypina.co.uk/login")
        server.enqueue(response)
    }

    private fun personalise(): PersonaliseResponse = api.personalise("tag-1", "04AABBCCDDEEFF")

    // ---- 401 / 3xx: NotSignedIn ---------------------------------------------------------------

    @Test fun `401 is NotSignedIn`() {
        enqueue(401, """{"error":"unauthorized"}""")
        assertFailsWith<ApiException.NotSignedIn> { personalise() }
    }

    @Test fun `302 redirect is NotSignedIn`() {
        enqueue(302)
        assertFailsWith<ApiException.NotSignedIn> { personalise() }
    }

    @Test fun `399 is NotSignedIn`() {
        enqueue(399)
        assertFailsWith<ApiException.NotSignedIn> { personalise() }
    }

    // ---- personalise errors --------------------------------------------------------------------

    @Test fun `personalise 400 malformed`() {
        enqueue(400, """{"error":"malformed"}""")
        assertFailsWith<ApiException.Malformed> { personalise() }
    }

    @Test fun `personalise 404 is TagNotFound`() {
        enqueue(404, """{"error":"notFound"}""")
        assertFailsWith<ApiException.TagNotFound> { personalise() }
    }

    @Test fun `personalise 409 uidTaken carries tag and shop`() {
        enqueue(
            409,
            """{"error":"uidTaken","tag":{"id":"t1","code":"ABC123","label":"Table 4"},"shop":{"id":"s1","name":"The Anchor"}}""",
        )
        val e = assertFailsWith<ApiException.UidTaken> { personalise() }
        assertEquals("ABC123", e.tag)
        assertEquals("The Anchor", e.shop)
        assertTrue(e.message!!.contains("ABC123"))
        assertTrue(e.message!!.contains("The Anchor"))
    }

    @Test fun `personalise 409 notApproved`() {
        enqueue(409, """{"error":"notApproved"}""")
        assertFailsWith<ApiException.NotApproved> { personalise() }
    }

    @Test fun `personalise 409 unsigned`() {
        enqueue(409, """{"error":"unsigned"}""")
        assertFailsWith<ApiException.Unsigned> { personalise() }
    }

    @Test fun `personalise 503 keys_missing`() {
        enqueue(503, """{"error":"keys_missing"}""")
        assertFailsWith<ApiException.KeysMissing> { personalise() }
    }

    // ---- personalised errors ---------------------------------------------------------------

    private fun personalised(): PersonalisedResponse = api.personalised("tag-1", "04AABBCCDDEEFF", "https://mypina.co.uk/t/ABC123")

    @Test fun `personalised 400 bad_cmac`() {
        enqueue(400, """{"error":"bad_cmac"}""")
        assertFailsWith<ApiException.BadCmac> { personalised() }
    }

    @Test fun `personalised 400 malformed`() {
        enqueue(400, """{"error":"malformed"}""")
        assertFailsWith<ApiException.Malformed> { personalised() }
    }

    @Test fun `personalised 400 uidMismatch`() {
        enqueue(400, """{"error":"uidMismatch"}""")
        assertFailsWith<ApiException.UidMismatch> { personalised() }
    }

    @Test fun `personalised 404 is TagNotFound`() {
        enqueue(404, """{"error":"notFound"}""")
        assertFailsWith<ApiException.TagNotFound> { personalised() }
    }

    @Test fun `personalised 409 replayed`() {
        enqueue(409, """{"error":"replayed"}""")
        assertFailsWith<ApiException.Replayed> { personalised() }
    }

    @Test fun `personalised 409 notApproved`() {
        enqueue(409, """{"error":"notApproved"}""")
        assertFailsWith<ApiException.NotApproved> { personalised() }
    }

    @Test fun `personalised 409 uidTaken`() {
        enqueue(409, """{"error":"uidTaken","tag":{"id":"t1","code":"ABC123"},"shop":{"id":"s1","name":"The Anchor"}}""")
        assertFailsWith<ApiException.UidTaken> { personalised() }
    }

    @Test fun `personalised 503 keys_missing`() {
        enqueue(503, """{"error":"keys_missing"}""")
        assertFailsWith<ApiException.KeysMissing> { personalised() }
    }

    // ---- unknown / unparseable errors -------------------------------------------------------

    @Test fun `unknown error code falls back to Rejected showing the code`() {
        enqueue(409, """{"error":"somethingNew"}""")
        val e = assertFailsWith<ApiException.Rejected> { personalise() }
        assertEquals("somethingNew", e.code)
        assertTrue(e.message!!.contains("somethingNew"))
    }

    @Test fun `unparseable error body falls back to Http`() {
        enqueue(500, "not json")
        val e = assertFailsWith<ApiException.Http> { personalise() }
        assertEquals(500, e.code)
    }

    // ---- success parsing, including forward-compatibility with unknown fields ------------------

    @Test fun `personalise success parses the full response and ignores unknown fields`() {
        enqueue(
            200,
            """
            {
              "tag": {"id":"t1","code":"ABC123","label":"Table 4","shopName":"The Anchor","futureField":"x"},
              "url": "https://mypina.co.uk/t/ABC123?e=00000000000000000000000000000000&c=0000000000000000",
              "ndefFileHex": "AABBCC",
              "sdm": {
                "fileOption":"40","accessRights":"00E0","sdmOptions":"C1","sdmAccessRights":"FF12",
                "piccDataOffset":34,"sdmMacInputOffset":69,"sdmMacOffset":69,"futureSdmField":1
              },
              "keys": {"key0":"00112233445566778899AABBCCDDEEFF0","key1":"11","key2":"22","keyVersion":1},
              "key0Candidates": ["00000000000000000000000000000000","11111111111111111111111111111111"],
              "futureTopLevelField": {"anything": true}
            }
            """.trimIndent(),
        )
        val response = personalise()
        assertEquals("ABC123", response.tag.code)
        assertEquals("Table 4", response.tag.label)
        assertEquals("The Anchor", response.tag.shopName)
        assertEquals(34, response.sdm.piccDataOffset)
        assertEquals(1, response.keys.keyVersion)
        assertEquals(2, response.key0Candidates.size)
    }

    // ---- request wiring ------------------------------------------------------------------------

    @Test fun `posts to the tag's path with the cookie for that URL, no retries`() {
        val seen = mutableListOf<String>()
        val api = AdminApi(
            baseUrl = server.url("/").toString(),
            cookieProvider = { url -> seen += url; "pina_a=abc" },
            client = AdminApi.defaultClient(),
        )
        enqueue(200, """{"ok":true,"counter":1}""")
        api.personalised("tag-1", "04AABBCCDDEEFF", "https://mypina.co.uk/t/ABC123")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/admin/api/tags/tag-1/personalised", request.path)
        assertEquals("pina_a=abc", request.getHeader("Cookie"))
        assertEquals(listOf(server.url("/admin/api/tags/tag-1/personalised").toString()), seen)
        assertFalse(AdminApi.defaultClient().retryOnConnectionFailure)
        assertFalse(AdminApi.defaultClient().followRedirects)
    }

    @Test fun `personalised 200 with ok false is not a success`() {
        enqueue(200, """{"ok":false,"counter":0}""")
        assertFailsWith<ApiException.BadResponse> { personalised() }
    }

    @Test fun `personalised success parses ok and counter`() {
        enqueue(200, """{"ok":true,"counter":42,"futureField":"x"}""")
        val response = personalised()
        assertTrue(response.ok)
        assertEquals(42L, response.counter)
    }

    // ---- verify ------------------------------------------------------------------------------

    private val chipUrl = "https://mypina.co.uk/t/ABC1234?e=EF963FF7828658A599F3041510671E88&c=94EED9EE65337086"

    private fun verify(tagId: String? = "tag-1"): VerifyResponse = api.verify(chipUrl, tagId)

    @Test fun `verify success parses the response and posts url and tagId with the cookie`() {
        val seen = mutableListOf<String>()
        val api = AdminApi(
            baseUrl = server.url("/").toString(),
            cookieProvider = { url -> seen += url; "pina_a=abc" },
            client = AdminApi.defaultClient(),
        )
        enqueue(
            200,
            """
            {"ok":true,
             "tag":{"id":"tag-1","code":"ABC1234","label":"Table 4","shopName":"The Anchor","encodedAt":"2026-09-20T10:00:00.000Z","future":1},
             "uid":"04958CAA5C5E80","counter":12,"lastCounter":11,"fresh":true,"uidMatches":true,"tagMatches":true,
             "futureField":"x"}
            """.trimIndent(),
        )
        val r = api.verify(chipUrl, "tag-1")
        assertTrue(r.ok && r.fresh && r.uidMatches && r.tagMatches)
        assertEquals("ABC1234", r.tag.code)
        assertEquals("Table 4", r.tag.label)
        assertEquals("The Anchor", r.tag.shopName)
        assertEquals("2026-09-20T10:00:00.000Z", r.tag.encodedAt)
        assertEquals("04958CAA5C5E80", r.uid)
        assertEquals(12L, r.counter)
        assertEquals(11L, r.lastCounter)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/admin/api/tags/verify", request.path)
        assertEquals("pina_a=abc", request.getHeader("Cookie"))
        assertEquals(listOf(server.url("/admin/api/tags/verify").toString()), seen)
        assertEquals("""{"url":"$chipUrl","tagId":"tag-1"}""", request.body.readUtf8())
    }

    @Test fun `verify without a tagId omits it and accepts a null lastCounter and a failed check`() {
        enqueue(
            200,
            """{"ok":true,"tag":{"id":"t2","code":"XYZ","shopName":null,"encodedAt":null},"uid":"04AA","counter":1,
               "lastCounter":null,"fresh":false,"uidMatches":false,"tagMatches":true}""",
        )
        val r = verify(tagId = null)
        assertEquals(null, r.lastCounter)
        assertEquals(null, r.tag.label)
        assertFalse(r.fresh)
        assertFalse(r.uidMatches)
        assertEquals("""{"url":"$chipUrl"}""", server.takeRequest().body.readUtf8())
    }

    @Test fun `verify 401 is NotSignedIn`() {
        enqueue(401, """{"error":"unauthorized"}""")
        assertFailsWith<ApiException.NotSignedIn> { verify() }
    }

    @Test fun `verify 400 malformed`() {
        enqueue(400, """{"error":"malformed"}""")
        assertFailsWith<ApiException.Malformed> { verify() }
    }

    @Test fun `verify 400 bad_cmac`() {
        enqueue(400, """{"error":"bad_cmac"}""")
        assertFailsWith<ApiException.BadCmac> { verify() }
    }

    @Test fun `verify 404 unknown_tag`() {
        enqueue(404, """{"error":"unknown_tag"}""")
        assertFailsWith<ApiException.UnknownTag> { verify() }
    }

    @Test fun `verify 409 unsigned`() {
        enqueue(409, """{"error":"unsigned"}""")
        assertFailsWith<ApiException.Unsigned> { verify() }
    }

    @Test fun `verify 503 keys_missing`() {
        enqueue(503, """{"error":"keys_missing"}""")
        assertFailsWith<ApiException.KeysMissing> { verify() }
    }
}
