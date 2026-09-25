package uk.co.mypina.admin.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep

/** The outcome of [connectIsoDep]. */
sealed interface IsoDepConnection {
    /** Connected, timeout set. The caller closes [iso]. */
    class Connected(val iso: IsoDep) : IsoDepConnection
    /** The tag has no ISO-DEP, so it isn't an NTAG 424 DNA. */
    data object NotIsoDep : IsoDepConnection
    /** Connecting failed (tag moved away, stale Tag, ...). */
    data object Failed : IsoDepConnection
}

/**
 * `IsoDep.get(tag)`, `connect()`, timeout 5000 ms (guide §5), shared by the Write and Verify
 * screens. Called on the NFC reader-mode binder thread, so it never throws: anything uncaught there
 * would crash the app.
 */
fun connectIsoDep(tag: Tag): IsoDepConnection {
    val iso = IsoDep.get(tag) ?: return IsoDepConnection.NotIsoDep
    return try {
        iso.connect()
        iso.timeout = 5000
        IsoDepConnection.Connected(iso)
    } catch (_: Exception) {
        // IOException / TagLostException, or SecurityException for a stale Tag.
        runCatching { iso.close() }
        IsoDepConnection.Failed
    }
}
