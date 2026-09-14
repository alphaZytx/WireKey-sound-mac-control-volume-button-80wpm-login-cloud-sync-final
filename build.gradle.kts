// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
    // Required by supabase-kt: its request/response models are kotlinx.serialization types,
    // and so are ours (ComposeDocument). Version-locked to the Kotlin plugin above.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0" apply false
}
