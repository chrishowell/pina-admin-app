package uk.co.mypina.admin.api

import android.webkit.CookieManager
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Errors from the admin API. Messages are shown to the person as-is. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 401 {"error":"unauthorized"} or any redirect (the site bounced us to its sign-in page). */
    class NotSignedIn : ApiException("Sign in on the website first.")
    /** 404 unknown_tag (verify): no tag has the code in the chip's URL. */
    class UnknownTag : ApiException("No tag has the code in this chip's URL.")
    /** 404: the server doesn't know this tag id. */
    class TagNotFound : ApiException("The server doesn't know this tag. Go back and reload the page.")
    /** 409 uidTaken: this chip's UID is already claimed by another tag. */
    class UidTaken(val tag: String?, val shop: String?) : ApiException(
        buildString {
            append("This chip already belongs to")
            if (tag != null) append(" tag $tag")
            if (shop != null) append(" at $shop")
            if (tag == null && shop == null) append(" another tag")
            append(". Move it on the website first.")
        },
    )
    /** 409 notApproved: the shop that owns this tag isn't approved yet. */
    class NotApproved : ApiException("This shop isn't approved yet. Approve it on the website first.")
    /** 409 unsigned (personalise): a demo tag with auth_mode none, which can't be written. */
    class Unsigned : ApiException("This is a demo tag and can't be written.")
    /** 409 replayed (personalised): the read-back counter was refused, a race with another read. */
    class Replayed : ApiException("The server rejected the read-back counter. Hold the tag again.")
    /** 503 keys_missing: the server has no NFC keys configured. */
    class KeysMissing : ApiException("The server has no NFC keys configured.")
    /** 400 bad_cmac (personalised, verify): the chip's SDM signature didn't verify. */
    class BadCmac : ApiException("The chip's signature didn't verify. Hold the tag again to rewrite it.")
    /** 400 malformed: the request body or UID the app sent was malformed. */
    class Malformed : ApiException("The request was malformed (a bug in the app).")
    /** 400 uidMismatch (personalised): the read-back URL is for a different UID than expected. */
    class UidMismatch : ApiException("The chip's read-back is for a different UID.")
    /** Any other error code the server sent: shown as-is so it's still actionable. */
    class Rejected(val code: String) : ApiException("The server rejected the request ($code).")
    class Http(val code: Int) : ApiException("Server error (HTTP $code).")
    class BadResponse : ApiException("Unexpected response from the server.")
    class Network(cause: IOException) : ApiException("Couldn't reach the server. Check the connection.", cause)
}

/**
 * Blocking client for the admin tag endpoints (personalise, personalised, verify). Call from a background thread.
 * Authenticates with the web view's own session cookie (pina_a); stores nothing itself.
 * Never logs request or response bodies (they carry keys).
 */
class AdminApi(
    private val baseUrl: String,
    /** Cookie header for a request URL. The full URL, so a cookie with a narrower Path still matches. */
    private val cookieProvider: (url: String) -> String? = { CookieManager.getInstance().getCookie(it) },
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun personalise(tagId: String, uid: String): PersonaliseResponse =
        post(
            tagId, "personalise",
            json.encodeToString(PersonaliseRequest.serializer(), PersonaliseRequest(uid)),
            PersonaliseResponse.serializer(),
        )

    fun personalised(tagId: String, uid: String, url: String): PersonalisedResponse =
        post(
            tagId, "personalised",
            json.encodeToString(PersonalisedRequest.serializer(), PersonalisedRequest(uid, url)),
            PersonalisedResponse.serializer(),
        ).also { if (!it.ok) throw ApiException.BadResponse() }

    /**
     * POST /admin/api/tags/verify: checks a URL read from a chip (real e= and c= values). [tagId]
     * is the tag the admin opened the screen from, if any; the server then says whether the chip
     * is that tag. Sends no keys and receives none.
     */
    fun verify(url: String, tagId: String?): VerifyResponse =
        post(
            listOf("verify"),
            json.encodeToString(VerifyRequest.serializer(), VerifyRequest(url, tagId)),
            VerifyResponse.serializer(),
        )

    private fun <T> post(tagId: String, action: String, body: String, serializer: KSerializer<T>): T =
        post(listOf(tagId, action), body, serializer)

    private fun <T> post(segments: List<String>, body: String, serializer: KSerializer<T>): T {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("admin/api/tags")
            .apply { segments.forEach { addPathSegment(it) } }
            .build()
            .toString()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { cookieProvider(url)?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }
            .post(body.toRequestBody(JSON_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                if (code == 401 || code in 300..399) throw ApiException.NotSignedIn()
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    // Don't surface parser messages: they can quote the body, which holds keys.
                    return try {
                        json.decodeFromString(serializer, text)
                    } catch (_: Exception) {
                        throw ApiException.BadResponse()
                    }
                }
                throw errorFor(code, text)
            }
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
    }

    private fun errorFor(code: Int, text: String): ApiException {
        val err = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
        return when {
            code == 404 && err?.error == "unknown_tag" -> ApiException.UnknownTag()
            code == 404 -> ApiException.TagNotFound()
            code == 409 && err?.error == "uidTaken" -> ApiException.UidTaken(err.tag.display(), err.shop.display())
            code == 409 && err?.error == "notApproved" -> ApiException.NotApproved()
            code == 409 && err?.error == "unsigned" -> ApiException.Unsigned()
            code == 409 && err?.error == "replayed" -> ApiException.Replayed()
            code == 400 && err?.error == "bad_cmac" -> ApiException.BadCmac()
            code == 400 && err?.error == "malformed" -> ApiException.Malformed()
            code == 400 && err?.error == "uidMismatch" -> ApiException.UidMismatch()
            code == 503 && err?.error == "keys_missing" -> ApiException.KeysMissing()
            err != null -> ApiException.Rejected(err.error)
            else -> ApiException.Http(code)
        }
    }

    private fun JsonElement?.display(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonObject -> listOf("code", "label", "name", "shopName", "id")
            .firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }
        else -> null
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            // No silent retries: a repeated POST .../personalised would be refused as a replay
            // after the first one had already succeeded (OkHttp also retries 408s by default).
            .retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
