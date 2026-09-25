package uk.co.mypina.admin.nfc

import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Byte vectors transcribed from NXP AN12196 rev 2.0 (4 March 2025), "NTAG 424 DNA and NTAG 424
 * DNA TagTamper features and hints". Table numbers are from that revision.
 *
 * Session A: Table 14 (AuthenticateEV2First, key 0 = zeros), then Table 17 (WriteData, CmdCtr 0)
 * and Table 18 (ChangeFileSettings, CmdCtr 1).
 * Session B: TI from Table 19 (AuthenticateEV2First key 3), session keys from Table 23
 * (AuthenticateEV2NonFirst key 0, which keeps TI and CmdCtr), then Table 24 (WriteData
 * CommMode.PLAIN, CmdCtr 1), Table 25 (ChangeKey key 2, CmdCtr 2) and Table 26 (ChangeKey key 0,
 * CmdCtr 3). Table 21 (WriteData FULL, CmdCtr 0) opened the counter in that session.
 * Table 7: GetFileSettings in CommMode.MAC, its own session (MAC key and TI given).
 */
object An12196 {
    const val ZERO_KEY = "00000000000000000000000000000000"

    // Table 7 (GetFileSettings in CommMode.MAC; only KSesAuthMAC is given)
    const val T7_MAC = "8248134A386E86EB7FAF54A52E536CB6"
    const val T7_TI = "7A21085E"
    const val T7_CMD = "90F5000009026597A457C8CD442C00"
    const val T7_DATA = "0040EEEE000100D1FE001F00004400004400002000006A0000"
    val T7_RESP = T7_DATA + "2A474282E7A47986" + "9100"

    // Table 14
    const val T14_CMD1 = "9071000002000000"
    const val T14_RESP1 = "A04C124213C186F22399D33AC2A3021591AF"
    const val T14_RND_A = "13C5DB8A5930439FC3DEF9A4C675360F"
    const val T14_RND_B = "B9E2FC789B64BF237CCCAA20EC7E6E48"
    const val T14_CMD2 = "90AF00002035C3E05A752E0144BAC0DE51C1F22C56B34408A23D8AEA266CAB947EA8E0118D00"
    const val T14_RESP2 = "3FA64DB5446D1F34CD6EA311167F5E4985B89690C04A05F17FA7AB2F081206639100"
    const val A_TI = "9D00C4DF"
    const val A_ENC = "1309C877509E5A215007FF0ED19CA564"
    const val A_MAC = "4C6626F5E72EA694202139295C7A7FC7"

    // Table 15/16: the NDEF file content (0x53 bytes). Table 17 writes it padded with zeros to 0x80.
    const val NDEF_URL = "https://choose.url.com/ntag424?e=00000000000000000000000000000000&c=0000000000000000"
    val NDEF_53 = "0051D1014D550463686F6F73652E75726C2E636F6D2F6E7461673432343F653D" +
        "30".repeat(32) + "26633D" + "30".repeat(16)
    val T17_DATA = NDEF_53 + "00".repeat(0x80 - 0x53)

    // Table 17. NB step 4/12 of the table say length 530000; the C-APDU (step 15) says 800000 and
    // only 800000 reproduces the table's MAC (step 13/14). The data really is 0x80 bytes.
    const val T17_IVC = "D2CB7277A17841A06654A48188C1F8F5"
    const val T17_ENC = "421C73A27D827658AF481FDFF20A5025B559D0E3AA21E58D347F343CFFC768BFE596C706BC00F2176781D4B0242642" +
        "A0FF5A42C461AAF894D9A1284B8C76BCFA658ACD40555D362E08DB15CF421B51283F9064BCBE20E96CAE545B40" +
        "7C9D651A3315B27373772E5DA2367D2064AE054AF996C6F1F669170FA88CE8C4E3A4A7BBBEF0FD971FF532C3A802" +
        "AF745660F2B4"
    const val T17_CMAC = "A8D185D964A8E04998965461E7EB3EF3"
    val T17_CMD = "908D00009F02000000800000" + T17_ENC + "D1D9A8499661EBF3" + "00"
    const val T17_RESP = "FC222E5F7A5424529100"

    // Table 18
    const val T18_DATA = "4000E0C1F121200000430000430000"
    const val T18_CMD = "905F0000190261B6D97903566E84C3AE5274467E89EAD799B7C1A0EF7A0400"
    const val T18_RESP = "57BFF87B1241E93D9100"

    // Table 22
    const val T22_SELECT = "00A4040C07D276000085010100"
    const val T22_RESP = "9000"

    // Table 19 (TI) + Table 23 (NonFirst session keys)
    const val B_TI = "7614281A"
    const val T23_RND_A = "60BE759EDA560250AC57CDDC11743CF6"
    const val T23_RND_B = "6924E8D09722659A2E7DEC68E66312B8"
    const val B_ENC = "4CF3CB41A22583A61E89B158D252FC53"
    const val B_MAC = "5529860B2FC5FB6154B7F28361D30BF9"

    // Table 24 (WriteData CommMode.PLAIN, CC file, in session B between Table 23 and Table 25)
    const val T24_DATA = "FF0506E10500808283000000000000000000"
    const val T24_CMD = "908D000019010E0000120000FF0506E1050080828300000000000000000000"
    const val T24_RESP = "9100"

    // Table 25 (key 2, old = zeros, CmdCtr 2)
    const val T25_NEW_KEY = "F3847D627727ED3BC9C4CC050489B966"
    const val T25_CRC = "789DFADC"
    const val T25_IVE = "307EDE1814707F30CFE603DD6CA62353"
    const val T25_ENC = "2CF362B7BF4311FF3BE1DAA295E8C68DE09050560D19B9E16C2393AE9CD1FAC7"
    const val T25_CMAC = "EA5D2E0CBFE24C0BCBCD501D21060EE6"
    val T25_CMD = "90C400002902" + T25_ENC + "5D0CE20BCD1D06E6" + "00"
    const val T25_RESP = "203BB55D1089D5879100"

    // Table 26 (key 0, CmdCtr 3)
    const val T26_NEW_KEY = "5004BF991F408672B1EF00F08F9E8647"
    const val T26_IVC = "01602D579423B2797BE8B478B0B4D27B"
    const val T26_ENC = "C0EB4DEEFEDDF0B513A03A95A75491818580503190D4D05053FF75668A01D6FD"
    const val T26_CMAC = "B7A60161F202EC3489BD4BEDEF64BB32"
    val T26_CMD = "90C400002900" + T26_ENC + "A6610234BDED6432" + "00"
    const val T26_RESP = "9100"

    // Table 28 (GetCardUid; session keys given directly)
    const val T28_ENC = "2B4D963C014DC36F24F69A50A394F875"
    const val T28_MAC = "379D32130CE61705DD5FD8C36B95D764"
    const val T28_TI = "DF055522"
    const val T28_CMD = "90510000088E2C155ADDA99BE300"
    const val T28_RESP = "70756055688505B52A5E26E59E329CD6595F672298EA41B79100"
    const val T28_UID = "04958CAA5C5E80"
}

/**
 * An independent implementation of the AES secure-messaging crypto (NT4H2421Gx §9.1, AN12196 §4),
 * written from the datasheet with javax.crypto and no code from the ntag424 library. Its own
 * outputs are pinned to AN12196 values in [An12196VectorsTest], so it can then check commands the
 * application note has no vector for (e.g. ChangeKey 1 at CmdCtr 2 of session A).
 */
object Ref {
    fun aesEcb(key: ByteArray, block: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(block) }

    fun cbc(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(data) }

    /** AES-CMAC per RFC 4493. */
    fun cmac(key: ByteArray, msg: ByteArray): ByteArray {
        fun dbl(b: ByteArray): ByteArray {
            val out = ByteArray(16)
            for (i in 0 until 16) {
                val next = if (i < 15) (b[i + 1].toInt() and 0xFF) ushr 7 else 0
                out[i] = ((b[i].toInt() shl 1) or next).toByte()
            }
            if (b[0].toInt() and 0x80 != 0) out[15] = (out[15].toInt() xor 0x87).toByte()
            return out
        }
        val l = aesEcb(key, ByteArray(16))
        val k1 = dbl(l)
        val k2 = dbl(k1)
        val n = if (msg.isEmpty()) 1 else (msg.size + 15) / 16
        val complete = msg.isNotEmpty() && msg.size % 16 == 0
        val last = ByteArray(16)
        val lastStart = (n - 1) * 16
        if (complete) {
            for (i in 0 until 16) last[i] = (msg[lastStart + i].toInt() xor k1[i].toInt()).toByte()
        } else {
            val rem = msg.size - lastStart
            val padded = ByteArray(16).also { msg.copyInto(it, 0, lastStart, msg.size); it[rem] = 0x80.toByte() }
            for (i in 0 until 16) last[i] = (padded[i].toInt() xor k2[i].toInt()).toByte()
        }
        var x = ByteArray(16)
        for (blk in 0 until n - 1) {
            x = aesEcb(key, ByteArray(16) { (x[it].toInt() xor msg[blk * 16 + it].toInt()).toByte() })
        }
        return aesEcb(key, ByteArray(16) { (x[it].toInt() xor last[it].toInt()).toByte() })
    }

    /** MACt: the odd-indexed bytes (1, 3, ... 15) of the CMAC. */
    fun macT(full: ByteArray) = ByteArray(8) { full[it * 2 + 1] }

    fun sv(prefix: String, a: ByteArray, b: ByteArray): ByteArray =
        prefix.hexToBytes() + "00010080".hexToBytes() + a.copyOfRange(0, 2) +
            ByteArray(6) { (a[2 + it].toInt() xor b[it].toInt()).toByte() } + b.copyOfRange(6, 16) + a.copyOfRange(8, 16)

    /** (KSesAuthENC, KSesAuthMAC) from key, RndA, RndB. */
    fun sessionKeys(key: ByteArray, rndA: ByteArray, rndB: ByteArray) =
        cmac(key, sv("A55A", rndA, rndB)) to cmac(key, sv("5AA5", rndA, rndB))

    /** JAMCRC (CRC32 without the final inversion), little-endian, as ChangeKey uses. */
    fun jamCrc(data: ByteArray): ByteArray {
        val c = CRC32().apply { update(data) }.value xor 0xFFFFFFFFL
        return ByteArray(4) { (c ushr (8 * it)).toByte() }
    }

    fun pad(data: ByteArray) = ByteArray((data.size / 16 + 1) * 16).also { data.copyInto(it); it[data.size] = 0x80.toByte() }
}

/** The chip side of one authenticated session, built on [Ref]. */
class RefSession(private val enc: ByteArray, private val mac: ByteArray, private val ti: ByteArray, var ctr: Int) {
    constructor(encHex: String, macHex: String, tiHex: String, ctr: Int) : this(encHex.hexToBytes(), macHex.hexToBytes(), tiHex.hexToBytes(), ctr)

    private fun ctrLe(c: Int) = byteArrayOf(c.toByte(), (c shr 8).toByte())
    private fun iv(prefix: String, c: Int) = Ref.aesEcb(enc, prefix.hexToBytes() + ti + ctrLe(c) + ByteArray(8))

    fun ivCmd() = iv("A55A", ctr)

    /**
     * Checks a native command APDU (`90 INS 00 00 Lc header [enc] MACt 00`) sent in this session at
     * the current CmdCtr: framing, header and MAC. Returns the decrypted (still padded) data, or an
     * empty array for a command with no data.
     */
    fun verifyCommand(apdu: ByteArray, ins: Int, header: ByteArray, decrypt: Boolean = true): ByteArray {
        ok(apdu[0] == 0x90.toByte() && apdu[1] == ins.toByte() && apdu[2] == 0.toByte() && apdu[3] == 0.toByte()) {
            "Bad framing: ${apdu.toHex()}"
        }
        val lc = apdu[4].toInt() and 0xFF
        ok(apdu.size == 5 + lc + 1 && apdu.last() == 0.toByte()) { "Bad Lc/Le: ${apdu.toHex()}" }
        val body = apdu.copyOfRange(5, 5 + lc)
        ok(body.copyOfRange(0, header.size).contentEquals(header)) { "Header ${body.copyOfRange(0, header.size).toHex()} != ${header.toHex()}" }
        val encData = body.copyOfRange(header.size, lc - 8)
        val macT = body.copyOfRange(lc - 8, lc)
        val expectedMac = Ref.macT(Ref.cmac(mac, byteArrayOf(ins.toByte()) + ctrLe(ctr) + ti + header + encData))
        ok(macT.contentEquals(expectedMac)) { "Command MAC wrong at CmdCtr $ctr" }
        return if (encData.isEmpty() || !decrypt) encData else Ref.cbc(Cipher.DECRYPT_MODE, enc, ivCmd(), encData)
    }

    /** A CommMode.MAC response: [data] in plain, MACt, 91 00; advances CmdCtr. */
    fun respondMac(data: ByteArray): ByteArray {
        ctr += 1
        val macT = Ref.macT(Ref.cmac(mac, byteArrayOf(0x00) + ctrLe(ctr) + ti + data))
        return data + macT + "9100".hexToBytes()
    }

    /** A CommMode.Plain response inside the session: bare 91 00, CmdCtr still advances. */
    fun respondPlain(): ByteArray {
        ctr += 1
        return "9100".hexToBytes()
    }

    /** A 91 00 response with MACt (and encrypted [plain], if given); advances CmdCtr. */
    fun respond(plain: ByteArray? = null): ByteArray {
        ctr += 1
        val encResp = if (plain == null) ByteArray(0) else Ref.cbc(Cipher.ENCRYPT_MODE, enc, iv("5AA5", ctr), Ref.pad(plain))
        val macT = Ref.macT(Ref.cmac(mac, byteArrayOf(0x00) + ctrLe(ctr) + ti + encResp))
        return encResp + macT + "9100".hexToBytes()
    }

    /** A bare 91 00 (ChangeKey 0 answers without a MAC); advances CmdCtr. */
    fun respondBare(): ByteArray {
        ctr += 1
        return "9100".hexToBytes()
    }
}

/**
 * The chip side of AuthenticateEV2First (NT4H2421Gx §9.1.5, AN12196 Table 14) for any key, built on
 * [Ref], so tests can authenticate with a key the application note has no vector for.
 */
class RefAuthChip(private val key: ByteArray, private val rndB: ByteArray, private val ti: ByteArray) {
    private val zeroIv = ByteArray(16)
    var rndA: ByteArray? = null
        private set

    /** 91 AF with E(K, RndB), the answer to `90 71 00 00 02 keyNo 00 00`. */
    fun part1(): ByteArray = Ref.cbc(Cipher.ENCRYPT_MODE, key, zeroIv, rndB) + "91AF".hexToBytes()

    /** Checks E(K, RndA ‖ RndB') and answers E(K, TI ‖ RndA' ‖ PDcap2 ‖ PCDcap2) 91 00. */
    fun part2(apdu: ByteArray): ByteArray {
        ok(apdu.size == 5 + 32 + 1 && apdu.copyOfRange(0, 5).toHex() == "90AF000020") { "Bad auth part 2: ${apdu.toHex()}" }
        val plain = Ref.cbc(Cipher.DECRYPT_MODE, key, zeroIv, apdu.copyOfRange(5, 37))
        val a = plain.copyOfRange(0, 16)
        val bPrime = rndB.copyOfRange(1, 16) + rndB[0]
        ok(plain.copyOfRange(16, 32).contentEquals(bPrime)) { "RndB' wrong: the PCD used another key" }
        rndA = a
        val aPrime = a.copyOfRange(1, 16) + a[0]
        return Ref.cbc(Cipher.ENCRYPT_MODE, key, zeroIv, ti + aPrime + ByteArray(12)) + "9100".hexToBytes()
    }

    /** The session this authentication opened, at CmdCtr 0. */
    fun session(): RefSession {
        val (enc, mac) = Ref.sessionKeys(key, rndA!!, rndB)
        return RefSession(enc, mac, ti, 0)
    }
}

/** Assertion that fails the test (an Error, so ChipSequence doesn't wrap it as a chip failure). */
private inline fun ok(cond: Boolean, msg: () -> String) {
    if (!cond) throw AssertionError(msg())
}
