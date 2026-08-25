package com.sagi.appleremotebridge

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

class CompanionIdentity(private val context: Context) {
    private val prefs = context.getSharedPreferences("companion_identity_runtime_v1", Context.MODE_PRIVATE)

    val identifier: String
        get() = prefs.getString("identifier", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("identifier", it).apply()
        }

    val deviceName: String
        get() = prefs.getString("device_name", null) ?: newName().also {
            prefs.edit().putString("device_name", it).apply()
        }

    val generation: Long
        get() = prefs.getLong("generation", 1L)

    fun rotate(): String {
        val name = newName()
        prefs.edit()
            .putString("identifier", UUID.randomUUID().toString())
            .putString("device_name", name)
            .putLong("generation", generation + 1L)
            .apply()
        return name
    }

    private fun newName(): String = "Xiaomi TV ${1000 + java.security.SecureRandom().nextInt(9000)}"

    private fun bytes(field: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest("AndroidAppleRemote-Runtime-v1\u0000$generation\u0000$field\u0000$identifier".toByteArray())
        .copyOfRange(0, 6)

    private fun hex(field: String) = bytes(field).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun bluetoothAddress(): String {
        val b = bytes("rpBA")
        b[0] = (((b[0].toInt() and 0xff) or 2) and 0xfe).toByte()
        return b.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }

    fun txtRecords() = mapOf(
        "rpMac" to "1", "rpHA" to hex("rpHA"), "rpHN" to hex("rpHN"),
        "rpVr" to "715.2", "rpMd" to "AppleTV14,1", "rpFl" to "0x36782",
        "rpAD" to hex("rpAD"), "rpHI" to hex("rpHI"), "rpBA" to bluetoothAddress(),
        "rpMRtID" to identifier
    )
}
