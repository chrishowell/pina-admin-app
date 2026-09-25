package uk.co.mypina.admin.nfc

/** Uppercase hex, the format the server uses for UIDs and keys. */
fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Odd-length hex" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
