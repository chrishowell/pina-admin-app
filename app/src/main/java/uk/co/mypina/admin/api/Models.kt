package uk.co.mypina.admin.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire types for the admin tag endpoints, per guide §4 (plus verify). Keys are uppercase hex strings.

@Serializable
data class PersonaliseRequest(val uid: String)

@Serializable
data class PersonalisedRequest(val uid: String, val url: String)

@Serializable
data class TagInfo(
    val id: String,
    val code: String,
    val label: String? = null,
    val shopName: String,
)

@Serializable
data class SdmSettings(
    val fileOption: String,      // "40"
    val accessRights: String,    // "00E0"
    val sdmOptions: String,      // "C1"
    val sdmAccessRights: String, // "FF12"
    val piccDataOffset: Int,
    val sdmMacInputOffset: Int,
    val sdmMacOffset: Int,
)

/** Secret. toString() is redacted so it can't leak via logs or exception messages. */
@Serializable
data class TagKeys(
    val key0: String,
    val key1: String,
    val key2: String,
    val keyVersion: Int,
) {
    override fun toString(): String = "TagKeys(<redacted>, keyVersion=$keyVersion)"
}

/**
 * POST /admin/api/tags/{tagId}/personalise. Holds keys: keep it only for one write, then drop it.
 * toString() is redacted.
 */
@Serializable
data class PersonaliseResponse(
    val tag: TagInfo,
    val url: String,          // https://mypina.co.uk/t/CODE?e=<32 zeros>&c=<16 zeros>
    val ndefFileHex: String,  // NLEN + URI record, written at offset 0 of file 02
    val sdm: SdmSettings,
    val keys: TagKeys,
    val key0Candidates: List<String>, // try in order when authenticating: factory zeros, then derived
) {
    override fun toString(): String = "PersonaliseResponse(tag=$tag, url=$url, sdm=$sdm, keys=<redacted>)"
}

/** POST /admin/api/tags/{tagId}/personalised */
@Serializable
data class PersonalisedResponse(val ok: Boolean, val counter: Long)

/** POST /admin/api/tags/verify. [tagId] is omitted (not sent as null) when absent. */
@Serializable
data class VerifyRequest(val url: String, val tagId: String? = null)

/** The tag row the chip's URL names, as the verify endpoint returns it. */
@Serializable
data class VerifiedTag(
    val id: String,
    val code: String,
    val label: String? = null,
    val shopName: String? = null,
    /** When the tag was last written (ISO 8601), or null if never. */
    val encodedAt: String? = null,
)

/**
 * 200 from POST /admin/api/tags/verify: the chip's SUN message verified. [counter] is the chip's
 * SDM read counter from this read, [lastCounter] the highest the server had seen before it
 * (null if none). [fresh] means counter > lastCounter; [uidMatches] that the chip's UID is the one
 * bound to [tag]; [tagMatches] that [tag] is the one the screen was opened for (true when no
 * tagId was sent).
 */
@Serializable
data class VerifyResponse(
    val ok: Boolean,
    val tag: VerifiedTag,
    val uid: String,
    val counter: Long,
    val lastCounter: Long? = null,
    val fresh: Boolean,
    val uidMatches: Boolean,
    val tagMatches: Boolean,
)

/**
 * Error bodies, per the Piña admin routes:
 *  - 401 { error: "unauthorized" } (treated as NotSignedIn, same as any 3xx redirect)
 *  - personalise: 400 { error: "malformed" }; 404; 409 { error: "uidTaken", tag, shop } |
 *    { error: "notApproved" } | { error: "unsigned" }; 503 { error: "keys_missing" }
 *  - personalised: 400 { error: "bad_cmac" | "malformed" | "uidMismatch" }; 404;
 *    409 { error: "notApproved" | "uidTaken" | "replayed" }; 503 { error: "keys_missing" }
 *  - verify: 400 { error: "malformed" | "bad_cmac" }; 404 { error: "unknown_tag" };
 *    409 { error: "unsigned" }; 503 { error: "keys_missing" }
 *  - identify: 400 { error: "malformed" } (bad uid)
 * `tag` and `shop` are kept loose (string or object) since only their display text is used.
 * Unrecognised codes fall back to [uk.co.mypina.admin.api.ApiException.Rejected], which shows
 * the code as-is.
 */
@Serializable
data class ErrorBody(
    val error: String,
    val tag: JsonElement? = null,
    val shop: JsonElement? = null,
)

/** POST /admin/api/tags/identify. [url] is omitted (not sent as null) when the chip had none. */
@Serializable
data class IdentifyRequest(val uid: String, val url: String? = null)

/** A tag row as the identify endpoint describes it. */
@Serializable
data class TagSummary(
    val id: String,
    val code: String,
    val label: String? = null,
    val shopId: String,
    val shopName: String,
    val shopStatus: String,
    val active: Boolean,
    val authMode: String,
    /** When the tag was last written (ISO 8601), or null if never. */
    val encodedAt: String? = null,
    val lastCounter: Long,
    val keyVersion: Int? = null,
)

/**
 * What the server made of the URL read from the chip. [signature] is one of "ok", "bad_cmac",
 * "malformed", "unsigned", "unknown_tag", "keys_missing". [tagCode] is the code in the URL (if it
 * is a Piña tap URL) and [urlTag] the row with that code (if any).
 */
@Serializable
data class IdentifiedChip(
    val url: String,
    val tagCode: String? = null,
    val urlTag: TagSummary? = null,
    val signature: String,
    val counter: Long? = null,
    val fresh: Boolean? = null,
    val uidMatches: Boolean? = null,
)

/**
 * 200 from POST /admin/api/tags/identify. [boundTag] is the row this chip's UID is bound to (if
 * any); [chip] is null when no URL was sent (blank chip, or not an NTAG 424 DNA).
 */
@Serializable
data class IdentifyResponse(
    val uid: String,
    val boundTag: TagSummary? = null,
    val chip: IdentifiedChip? = null,
)
