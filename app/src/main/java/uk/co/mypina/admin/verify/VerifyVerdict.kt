package uk.co.mypina.admin.verify

import uk.co.mypina.admin.api.VerifyResponse

/** The big line on the Verify result card, and why it isn't a clean pass. */
data class Verdict(val verified: Boolean, val title: String, val reasons: List<String>)

/**
 * "Tag verified" only when the server says ok and the read is fresh, the UID is the bound one and
 * the chip is the tag the screen was opened for. Otherwise "Verified, but…" with each reason.
 * (A signature that doesn't verify never gets here: the server answers 400 bad_cmac.)
 */
fun verdictFor(r: VerifyResponse): Verdict {
    val reasons = buildList {
        if (!r.ok) add("the server didn't confirm the read")
        if (!r.fresh) add("counter ${r.counter} was already seen, server has ${r.lastCounter ?: "none"}")
        if (!r.uidMatches) add("chip UID ${r.uid} isn't the one bound to this tag")
        if (!r.tagMatches) add("this chip is tag ${r.tag.code}, not the one you opened")
    }
    return if (reasons.isEmpty()) {
        Verdict(verified = true, title = "Tag verified", reasons = emptyList())
    } else {
        Verdict(verified = false, title = "Verified, but…", reasons = reasons)
    }
}

/** The tag code from a Piña tap URL (`…/t/CODE?e=…&c=…`), or null if it isn't one. */
fun tagCodeFromUrl(url: String): String? =
    Regex("/t/([^/?#]+)").find(url.substringBefore('?').substringBefore('#'))?.groupValues?.get(1)
