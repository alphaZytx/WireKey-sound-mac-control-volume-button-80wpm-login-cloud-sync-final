package com.wirekey.bluetooth

import com.wirekey.util.HidKeyCodes

object HidReportBuilder {

    /**
     * Builds an 8-byte standard HID keyboard report for a key press.
     * Byte 0: Modifier keys (Ctrl, Shift, Alt, GUI)
     * Byte 1: Reserved (0x00)
     * Byte 2-7: Keycodes (up to 6 keys pressed simultaneously)
     */
    fun keyDownReport(keyCode: Int, modifiers: Int = 0): ByteArray {
        val report = ByteArray(8)
        report[0] = modifiers.toByte()
        report[1] = 0x00.toByte()
        report[2] = keyCode.toByte()
        report[3] = 0x00.toByte()
        report[4] = 0x00.toByte()
        report[5] = 0x00.toByte()
        report[6] = 0x00.toByte()
        report[7] = 0x00.toByte()
        return report
    }

    /**
     * Builds an 8-byte standard HID keyboard report for all keys released.
     */
    fun keyUpReport(): ByteArray {
        return ByteArray(8)
    }

    /**
     * Looks up the character in [HidKeyCodes.charMap].
     * Returns a Pair containing the key-down and key-up reports.
     * Returns null if the character has no mapping.
     */
    fun charToReports(char: Char): Pair<ByteArray, ByteArray>? {
        val mapping = HidKeyCodes.charMap[char] ?: return null
        val keyCode = mapping.first
        val needsShift = mapping.second

        val modifiers = if (needsShift) HidKeyCodes.MODIFIER_LEFT_SHIFT else 0
        
        val downReport = keyDownReport(keyCode, modifiers)
        val upReport = keyUpReport()
        
        return Pair(downReport, upReport)
    }
}
