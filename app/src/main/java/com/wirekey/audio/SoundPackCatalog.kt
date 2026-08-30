package com.wirekey.audio

/** One bundled keyboard soundpack: [id] is also its asset subfolder under assets/sounds/. */
data class SoundPackInfo(val id: String, val displayName: String)

/**
 * Static list of the two soundpacks sliced by scripts/generate_macbook_pack.py from real
 * keystroke recordings in Keystroke-Datasets-main/ into assets/sounds/<id>/: MBPWavs (a
 * phone mic set down next to the MacBook) and Zoom (the same physical typing captured
 * through Zoom's built-in meeting recorder, so it has its own distinct mic/compression
 * color). Kept as a fixed list (rather than scanning assets at runtime) since the set
 * only changes when the generator script is re-run against new source recordings.
 */
object SoundPackCatalog {
    val ALL_PACKS: List<SoundPackInfo> = listOf(
        SoundPackInfo("macbook-real", "Real Sound Mode"),
        SoundPackInfo("macbook-zoom", "Zoom Recording"),
    )

    const val DEFAULT_PACK_ID = "macbook-real"
}
