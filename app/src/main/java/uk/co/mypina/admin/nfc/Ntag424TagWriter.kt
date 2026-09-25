package uk.co.mypina.admin.nfc

import android.nfc.tech.IsoDep
import uk.co.mypina.admin.api.AdminApi

/**
 * The real writer: drives an NTAG 424 DNA chip through [ChipSequence] (or [ChipReset]) over
 * [IsoDep], with the server calls from [AdminApi]. All chip logic lives in those two so it can be
 * unit tested without Android.
 */
class Ntag424TagWriter(private val api: AdminApi) : TagWriter {
    override fun write(iso: IsoDep, tagId: String, onStep: (Step, StepState) -> Unit): WriteResult =
        ChipSequence(
            transceive = { iso.transceive(it) },
            personalise = { uid -> api.personalise(tagId, uid) },
            personalised = { uid, url -> api.personalised(tagId, uid, url) },
        ).run(iso.tag.id, onStep)

    override fun reset(iso: IsoDep, tagId: String, onStep: (Step, StepState) -> Unit): ResetResult =
        ChipReset(
            transceive = { iso.transceive(it) },
            personalise = { uid -> api.personalise(tagId, uid) },
        ).run(iso.tag.id, onStep)
}
