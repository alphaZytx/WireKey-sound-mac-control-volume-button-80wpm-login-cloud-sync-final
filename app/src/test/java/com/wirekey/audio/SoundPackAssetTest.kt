package com.wirekey.audio

import com.wirekey.util.HidKeyCodes
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the soundpack assets themselves against the two failure modes that are invisible
 * from Kotlin — nothing throws, the app just sounds wrong:
 *
 *  1. *A key with no asset is silently mute.* AcousticTypingEngine.loadPack() treats a
 *     missing file as "this pack has no sound for this key" and plays nothing, so a
 *     renamed or unregenerated pack degrades to silence instead of failing. The clip
 *     naming did change once already (`KeyA_down.wav` -> `KeyA_down_0.wav`).
 *
 *  2. *Long clips turn fast typing into a wash.* Above ~80 WPM keystrokes are less than
 *     150 ms apart, so clips must finish well inside that or each stroke is heard on top
 *     of its predecessors' tails rather than as a distinct click. The packs previously
 *     shipped 178 ms clips whose last 120 ms was amplified room tone.
 *
 * Reads the asset tree directly off disk (unit tests run with the module directory as
 * their working directory) rather than through AssetManager, which needs a device.
 */
class SoundPackAssetTest {

    private val assetsRoot = File("src/main/assets/sounds")

    /** Every physical key the HID layer can send, plus the shift used for capital chords. */
    private val requiredKeyNames: Set<String> =
        HidKeyCodes.physicalKeyName.values.toSet() + "ShiftLeft"

    private fun packDir(packId: String) = File(assetsRoot, packId)

    @Test
    fun everyCatalogPackShipsItsAssetDirectory() {
        for (pack in SoundPackCatalog.ALL_PACKS) {
            val dir = packDir(pack.id)
            assertTrue(
                "Sound pack '${pack.id}' is in SoundPackCatalog but has no assets at ${dir.path} " +
                    "— every key in it would play silently. Re-run scripts/generate_macbook_pack.py.",
                dir.isDirectory
            )
        }
    }

    @Test
    fun everySendableKeyHasAPressAndReleaseClipInEveryPack() {
        val missing = mutableListOf<String>()
        for (pack in SoundPackCatalog.ALL_PACKS) {
            val dir = packDir(pack.id)
            if (!dir.isDirectory) continue
            for (keyName in requiredKeyNames) {
                for (suffix in listOf("down", "up")) {
                    // Variant 0 is the one the engine can never fall back from: it stops
                    // scanning at the first gap, so a missing _0 mutes the key entirely.
                    if (!File(dir, "${keyName}_${suffix}_0.wav").isFile) {
                        missing += "${pack.id}/${keyName}_${suffix}_0.wav"
                    }
                }
            }
        }
        assertTrue("Keys that would play no sound (missing clips): $missing", missing.isEmpty())
    }

    @Test
    fun pressClipsHaveMoreThanOneVariant() {
        // Repeated letters replaying one identical waveform is the most audible sampler
        // tell; the engine picks a different variant each time, but only if one exists.
        val singleVariant = mutableListOf<String>()
        for (pack in SoundPackCatalog.ALL_PACKS) {
            val dir = packDir(pack.id)
            if (!dir.isDirectory) continue
            for (keyName in requiredKeyNames) {
                if (!File(dir, "${keyName}_down_1.wav").isFile) {
                    singleVariant += "${pack.id}/$keyName"
                }
            }
        }
        assertTrue(
            "Keys with only one press variant (repeats will sound identical): $singleVariant",
            singleVariant.isEmpty()
        )
    }

    @Test
    fun clipsAreShortEnoughToStayDistinctAtHighWpm() {
        val tooLong = mutableListOf<String>()
        for (pack in SoundPackCatalog.ALL_PACKS) {
            val dir = packDir(pack.id)
            if (!dir.isDirectory) continue
            for (file in dir.listFiles { f -> f.extension == "wav" }.orEmpty()) {
                val ms = wavDurationMs(file) ?: continue
                val limit = if (file.name.contains("_up_")) MAX_RELEASE_MS else MAX_PRESS_MS
                if (ms > limit) tooLong += "${pack.id}/${file.name} = ${ms.toInt()}ms (max ${limit.toInt()})"
            }
        }
        assertTrue("Clips long enough to smear into the next keystroke: $tooLong", tooLong.isEmpty())
    }

    /** Minimal RIFF/WAVE header walk — enough for the PCM files the generator writes. */
    private fun wavDurationMs(file: File): Double? {
        val bytes = file.readBytes()
        if (bytes.size < 44) return null
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null

        fun le32(at: Int) = (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

        var byteRate = 0
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4)
            val size = le32(offset + 4)
            if (size < 0) return null
            when (id) {
                "fmt " -> if (offset + 16 <= bytes.size) byteRate = le32(offset + 16)
                "data" -> return if (byteRate > 0) size * 1000.0 / byteRate else null
            }
            offset += 8 + size + (size and 1) // chunks are word-aligned
        }
        return null
    }

    companion object {
        // At 120 WPM keystrokes are 100 ms apart; the generator targets ~101 ms presses
        // with the tail tapered to silence, so this leaves headroom without allowing a
        // return to the 178 ms clips that caused the wash.
        private const val MAX_PRESS_MS = 130.0
        private const val MAX_RELEASE_MS = 70.0
    }
}
