package com.wirekey.bluetooth

import com.wirekey.util.HidKeyCodes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HidReportBuilderTest {

    @Test
    fun testLowercaseLetter() {
        val reports = HidReportBuilder.charToReports('a')
        assertNotNull(reports)
        val (down, up) = reports!!
        
        val expectedDown = ByteArray(8)
        expectedDown[2] = 0x04.toByte() // 'a' keycode
        
        assertArrayEquals(expectedDown, down)
        assertArrayEquals(ByteArray(8), up)
    }

    @Test
    fun testUppercaseLetter() {
        val reports = HidReportBuilder.charToReports('Z')
        assertNotNull(reports)
        val (down, up) = reports!!
        
        val expectedDown = ByteArray(8)
        expectedDown[0] = HidKeyCodes.MODIFIER_LEFT_SHIFT.toByte()
        expectedDown[2] = 0x1D.toByte() // 'z' keycode
        
        assertArrayEquals(expectedDown, down)
        assertArrayEquals(ByteArray(8), up)
    }

    @Test
    fun testDigit() {
        val reports = HidReportBuilder.charToReports('5')
        assertNotNull(reports)
        val (down, up) = reports!!
        
        val expectedDown = ByteArray(8)
        expectedDown[2] = 0x22.toByte() // '5' is 0x1E + 4 = 0x22
        
        assertArrayEquals(expectedDown, down)
        assertArrayEquals(ByteArray(8), up)
    }

    @Test
    fun testPunctuation() {
        val cases = mapOf(
            '!' to Pair(0x1E, true),
            '-' to Pair(0x2D, false),
            '{' to Pair(0x2F, true),
            '?' to Pair(0x38, true),
            ' ' to Pair(0x2C, false)
        )

        for ((char, mapping) in cases) {
            val reports = HidReportBuilder.charToReports(char)
            assertNotNull("Character '$char' should not return null", reports)
            val down = reports!!.first
            
            val expectedModifier = if (mapping.second) HidKeyCodes.MODIFIER_LEFT_SHIFT.toByte() else 0.toByte()
            assertEquals("Modifier mismatch for '$char'", expectedModifier, down[0])
            assertEquals("Keycode mismatch for '$char'", mapping.first.toByte(), down[2])
        }
    }

    @Test
    fun testUnsupportedCharacter() {
        // High surrogate (part of an emoji, not a valid single character for HID mapping)
        val emojiReports = HidReportBuilder.charToReports('\uD83D')
        assertNull(emojiReports)
        
        // Unsupported symbol in basic layout
        val symbolReports = HidReportBuilder.charToReports('ø')
        assertNull(symbolReports)
    }
}
