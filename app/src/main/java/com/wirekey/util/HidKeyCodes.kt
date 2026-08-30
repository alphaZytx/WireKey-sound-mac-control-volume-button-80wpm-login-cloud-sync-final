package com.wirekey.util

object HidKeyCodes {
    // Modifier bits
    const val MODIFIER_NONE = 0x00
    const val MODIFIER_LEFT_CTRL = 0x01
    const val MODIFIER_LEFT_SHIFT = 0x02
    const val MODIFIER_LEFT_ALT = 0x04
    const val MODIFIER_LEFT_GUI = 0x08
    const val MODIFIER_RIGHT_CTRL = 0x10
    const val MODIFIER_RIGHT_SHIFT = 0x20
    const val MODIFIER_RIGHT_ALT = 0x40
    const val MODIFIER_RIGHT_GUI = 0x80

    // Control Keys
    const val KEY_ENTER = 0x28
    const val KEY_ESCAPE = 0x29
    const val KEY_BACKSPACE = 0x2A
    const val KEY_TAB = 0x2B
    const val KEY_SPACE = 0x2C
    const val KEY_CAPSLOCK = 0x39
    const val KEY_SCROLLLOCK = 0x47
    const val KEY_NUMLOCK = 0x53
    
    // F1..F12
    const val KEY_F1 = 0x3A
    const val KEY_F2 = 0x3B
    const val KEY_F3 = 0x3C
    const val KEY_F4 = 0x3D
    const val KEY_F5 = 0x3E
    const val KEY_F6 = 0x3F
    const val KEY_F7 = 0x40
    const val KEY_F8 = 0x41
    const val KEY_F9 = 0x42
    const val KEY_F10 = 0x43
    const val KEY_F11 = 0x44
    const val KEY_F12 = 0x45

    // Navigation Keys
    const val KEY_RIGHT_ARROW = 0x4F
    const val KEY_LEFT_ARROW = 0x50
    const val KEY_DOWN_ARROW = 0x51
    const val KEY_UP_ARROW = 0x52
    const val KEY_DELETE = 0x4C // forward delete
    const val KEY_HOME = 0x4A
    const val KEY_END = 0x4D

    val charMap: Map<Char, Pair<Int, Boolean>> = buildCharMap()

    // HID keycode -> Mechvibes/JS KeyboardEvent.code physical key name, e.g. 0x04 -> "KeyA".
    // Single source of truth for the Acoustic Typing Feedback Engine's char-to-sound lookup:
    // it composes through charMap's keyCode rather than keying off Char directly, so if
    // charMap ever gains a new character its sound mapping can't silently drift out of sync —
    // it just has no physical key name yet, which resolves to "no sound" rather than a wrong one.
    val physicalKeyName: Map<Int, String> = buildPhysicalKeyNameMap()

    private fun buildPhysicalKeyNameMap(): Map<Int, String> {
        val map = mutableMapOf<Int, String>()

        for (i in 0..25) {
            map[0x04 + i] = "Key" + ('A' + i)
        }

        for (i in 1..9) {
            map[0x1E + (i - 1)] = "Digit$i"
        }
        map[0x27] = "Digit0"

        map[0x2D] = "Minus"
        map[0x2E] = "Equal"
        map[0x2F] = "BracketLeft"
        map[0x30] = "BracketRight"
        map[0x31] = "Backslash"
        map[0x33] = "Semicolon"
        map[0x34] = "Quote"
        map[0x35] = "Backquote"
        map[0x36] = "Comma"
        map[0x37] = "Period"
        map[0x38] = "Slash"

        map[KEY_SPACE] = "Space"
        map[KEY_ENTER] = "Enter"
        map[KEY_TAB] = "Tab"
        map[KEY_BACKSPACE] = "Backspace"

        return map
    }

    private fun buildCharMap(): Map<Char, Pair<Int, Boolean>> {
        val map = mutableMapOf<Char, Pair<Int, Boolean>>()

        // Letters a-z (0x04 to 0x1D)
        for (i in 0..25) {
            val charLower = 'a' + i
            val charUpper = 'A' + i
            val keyCode = 0x04 + i
            map[charLower] = Pair(keyCode, false)
            map[charUpper] = Pair(keyCode, true)
        }

        // Digits 1-9 (0x1E to 0x26), 0 (0x27)
        for (i in 1..9) {
            val char = '0' + i
            val keyCode = 0x1E + (i - 1)
            map[char] = Pair(keyCode, false)
        }
        map['0'] = Pair(0x27, false)

        // Shifted digits (US layout)
        val shiftedDigits = mapOf(
            '!' to 0x1E, '@' to 0x1F, '#' to 0x20, '$' to 0x21,
            '%' to 0x22, '^' to 0x23, '&' to 0x24, '*' to 0x25,
            '(' to 0x26, ')' to 0x27
        )
        shiftedDigits.forEach { (char, keyCode) ->
            map[char] = Pair(keyCode, true)
        }

        // Symbols and their shifted counterparts
        map['-'] = Pair(0x2D, false)
        map['_'] = Pair(0x2D, true)
        
        map['='] = Pair(0x2E, false)
        map['+'] = Pair(0x2E, true)
        
        map['['] = Pair(0x2F, false)
        map['{'] = Pair(0x2F, true)
        
        map[']'] = Pair(0x30, false)
        map['}'] = Pair(0x30, true)
        
        map['\\'] = Pair(0x31, false)
        map['|'] = Pair(0x31, true)
        
        map[';'] = Pair(0x33, false)
        map[':'] = Pair(0x33, true)
        
        map['\''] = Pair(0x34, false)
        map['"'] = Pair(0x34, true)
        
        map['`'] = Pair(0x35, false)
        map['~'] = Pair(0x35, true)
        
        map[','] = Pair(0x36, false)
        map['<'] = Pair(0x36, true)
        
        map['.'] = Pair(0x37, false)
        map['>'] = Pair(0x37, true)
        
        map['/'] = Pair(0x38, false)
        map['?'] = Pair(0x38, true)

        // Whitespace and specials
        map[' '] = Pair(KEY_SPACE, false)
        map['\n'] = Pair(KEY_ENTER, false)
        map['\t'] = Pair(KEY_TAB, false)

        return map
    }
}
