package uk.co.mypina.admin.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.util.Random

/**
 * A scripted chip. Each expected exchange either asserts the command APDU byte for byte and
 * replays a fixed response ([expect]), or hands the APDU to a checker that verifies it and
 * computes the response ([expectChecked]). Anything out of script fails the test.
 */
class FakeTransceiver : (ByteArray) -> ByteArray {
    private class Exchange(val label: String, val handle: (ByteArray) -> ByteArray)

    private val script = ArrayDeque<Exchange>()
    val sent = mutableListOf<String>()

    fun expect(label: String, commandHex: String, responseHex: String) = apply {
        script += Exchange(label) { apdu ->
            assertEquals("APDU for $label", commandHex.uppercase(), apdu.toHex())
            responseHex.hexToBytes()
        }
    }

    fun expectChecked(label: String, handle: (ByteArray) -> ByteArray) = apply {
        script += Exchange(label, handle)
    }

    override fun invoke(apdu: ByteArray): ByteArray {
        sent += apdu.toHex()
        val next = script.removeFirstOrNull() ?: fail("Unexpected APDU after the script ended: ${apdu.toHex()}").let { error("unreachable") }
        return next.handle(apdu)
    }

    fun assertDone() {
        if (script.isNotEmpty()) fail("Script not finished; next expected: ${script.first().label}")
    }
}

/** Makes the library's ByteUtil.random return a fixed RndA. */
class FixedRandom(private val bytes: ByteArray) : Random() {
    override fun nextBytes(out: ByteArray) {
        bytes.copyInto(out, 0, 0, out.size)
    }
}
