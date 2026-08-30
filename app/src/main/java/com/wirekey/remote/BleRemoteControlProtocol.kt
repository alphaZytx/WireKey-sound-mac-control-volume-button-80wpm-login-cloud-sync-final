package com.wirekey.remote

import java.util.UUID

/**
 * The intentionally tiny, versioned protocol shared by WireKey Android and WireKey Remote
 * for macOS. A valid packet is only `[1, 1]`: protocol version 1 and the toggle command.
 */
object BleRemoteControlProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("8d09a2c2-744f-4f17-b34f-0271d1b9a3d1")
    val COMMAND_CHARACTERISTIC_UUID: UUID = UUID.fromString("8d09a2c3-744f-4f17-b34f-0271d1b9a3d1")

    const val VERSION: Byte = 0x01
    const val TRIGGER: Byte = 0x01

    fun isTrigger(value: ByteArray?): Boolean =
        value?.contentEquals(byteArrayOf(VERSION, TRIGGER)) == true
}
