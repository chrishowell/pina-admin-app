package uk.co.mypina.admin.identify

import uk.co.mypina.admin.api.IdentifyResponse
import uk.co.mypina.admin.api.TagSummary
import uk.co.mypina.admin.nfc.TagReadException
import uk.co.mypina.admin.nfc.toHex
import uk.co.mypina.admin.verify.tagCodeFromUrl

/** What the keyless read of the chip found, before asking the server. */
sealed interface ChipRead {
    /** The URL in the chip's NDEF file (with its real e= and c=). */
    data class Url(override val url: String) : ChipRead
    /** Selected fine, but NLEN is 0 or the file holds no URI record. */
    data object Blank : ChipRead
    /** Not an NTAG 424 DNA: [reason] is e.g. "select refused" or "no ISO-DEP". */
    data class NotNtag424(val reason: String) : ChipRead
    /** The chip refused the ReadData; [message] carries its status word. */
    data class Refused(val message: String) : ChipRead

    /** The URL, when the chip had one. */
    val url: String? get() = null
}

/**
 * Runs [read] (TagVerifier's select + one plain ReadData) and folds the "no URL" outcomes into a
 * [ChipRead], so the chip can still be identified by UID. Anything else (a lost tag) is rethrown.
 */
fun readChip(read: () -> String): ChipRead = try {
    ChipRead.Url(read())
} catch (e: TagReadException) {
    when (e.kind) {
        TagReadException.Kind.NO_URL -> ChipRead.Blank
        TagReadException.Kind.NOT_NTAG424 -> ChipRead.NotNtag424("select refused")
        TagReadException.Kind.REFUSED -> ChipRead.Refused(e.message.orEmpty())
    }
}

/** The chip's UID from its ISO-DEP tag id: 7 bytes, not starting 08 (a Random ID), else null. */
fun uidFromTagId(id: ByteArray?): String? = id?.takeIf { it.size == 7 && it[0] != 0x08.toByte() }?.toHex()

const val RANDOM_ID_MESSAGE = "Random ID chip, not a Piña chip."

enum class Tone { GOOD, NEUTRAL, WARN, BAD }

/**
 * The Identify result card: the verdict line ([title]), bullet [notes], and the tag row worth
 * detailing ([tag]), with the chip's [counter] line when it has one. UID and URL are shown by the
 * screen at the foot of the card, whatever the verdict.
 */
data class IdentifyVerdict(
    val tone: Tone,
    val title: String,
    val notes: List<String> = emptyList(),
    val tag: TagSummary? = null,
    val counter: String? = null,
)

fun identifyVerdict(r: IdentifyResponse, read: ChipRead): IdentifyVerdict {
    val readNote = when (read) {
        is ChipRead.NotNtag424 -> "Not an NTAG 424 DNA (${read.reason})"
        is ChipRead.Refused -> read.message
        else -> null
    }
    val bound = r.boundTag
    val chip = r.chip

    if (chip == null) {
        val blank = read is ChipRead.Blank
        val v = if (bound == null) {
            IdentifyVerdict(
                Tone.NEUTRAL,
                if (blank) "Blank chip, not bound to any tag" else "Not bound to any tag",
            )
        } else {
            IdentifyVerdict(
                Tone.WARN,
                if (blank) "Blank chip, but its UID is bound to ${bound.display()}"
                else "Its UID is bound to ${bound.display()}",
                notes = if (blank) listOf("The tag's row expects this chip: write it from the tag's page.") else emptyList(),
                tag = bound,
            )
        }
        return v.copy(notes = listOfNotNull(readNote) + v.notes)
    }

    val urlTag = chip.urlTag
    val code = chip.tagCode ?: urlTag?.code ?: tagCodeFromUrl(chip.url) ?: "?"
    // Where the UID points, when that's a different row from the URL's.
    val boundElsewhere = bound?.takeIf { it.id != urlTag?.id }?.let { "The UID is bound to ${it.display()}" }

    val v = when (chip.signature) {
        "ok" -> {
            val counter = counterLine(chip.counter, chip.fresh, urlTag?.lastCounter)
            when {
                chip.uidMatches != true -> IdentifyVerdict(
                    Tone.BAD,
                    "The URL was written for a different chip (UID in URL ≠ this chip)",
                    notes = listOfNotNull("Signature OK for tag $code", boundElsewhere),
                    tag = urlTag,
                    counter = counter,
                )
                urlTag == null -> IdentifyVerdict(
                    Tone.WARN,
                    "Signature OK for tag $code, but the server returned no row for it",
                    notes = listOfNotNull(boundElsewhere),
                    counter = counter,
                )
                bound?.id == urlTag.id -> IdentifyVerdict(
                    Tone.GOOD,
                    "Tag ${urlTag.code} at ${urlTag.shopName}, signature OK",
                    tag = urlTag,
                    counter = counter,
                )
                else -> IdentifyVerdict(
                    Tone.WARN,
                    "Written for tag ${urlTag.code}, but the UID is bound to ${bound?.display() ?: "none"}",
                    tag = urlTag,
                    counter = counter,
                )
            }
        }
        "bad_cmac" -> IdentifyVerdict(
            Tone.BAD,
            "Signature invalid: written by another server or tampered",
            notes = listOfNotNull(
                if (chip.tagCode != null || urlTag != null) "The URL names tag $code" else null,
                boundElsewhere,
            ),
            tag = urlTag,
        )
        "unknown_tag" -> IdentifyVerdict(
            Tone.BAD,
            "URL points at tag $code, which doesn't exist on this server",
            notes = listOfNotNull(boundElsewhere),
        )
        "unsigned" -> IdentifyVerdict(
            Tone.NEUTRAL,
            "Demo tag $code (no signature)",
            notes = listOfNotNull(boundElsewhere),
            tag = urlTag,
        )
        "malformed" -> IdentifyVerdict(
            Tone.WARN,
            "Has a URL, but not a Piña tap URL",
            notes = listOfNotNull(boundElsewhere),
        )
        "keys_missing" -> IdentifyVerdict(
            Tone.WARN,
            "Server has no NFC keys; can't check the signature",
            notes = listOfNotNull(boundElsewhere),
            tag = urlTag,
        )
        else -> IdentifyVerdict(
            Tone.WARN,
            "The server gave an unknown signature result (${chip.signature})",
            notes = listOfNotNull(boundElsewhere),
            tag = urlTag,
        )
    }
    return v.copy(notes = listOfNotNull(readNote) + v.notes)
}

/** "12, fresh (server counter 11)" or "7, already seen (server counter 9)". */
internal fun counterLine(counter: Long?, fresh: Boolean?, serverCounter: Long?): String? {
    counter ?: return null
    val server = serverCounter?.toString() ?: "none"
    return when (fresh) {
        true -> "$counter, fresh (server counter $server)"
        false -> "$counter, already seen (server counter $server)"
        null -> "$counter (server counter $server)"
    }
}

private fun TagSummary.display() = "tag $code at $shopName"
