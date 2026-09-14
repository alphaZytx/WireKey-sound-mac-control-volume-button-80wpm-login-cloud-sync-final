import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Supabase project credentials, kept out of version control.
 *
 * Put them in local.properties (which is gitignored) as:
 *   supabase.url=https://<project-ref>.supabase.co
 *   supabase.anonKey=<publishable / anon key>
 *
 * Environment variables of the same name win, for CI. Both may legitimately be absent:
 * the app compiles and runs without them, with cloud sync simply switched off, so an
 * unconfigured checkout still builds. Only the *anon* (publishable) key belongs here —
 * it is safe to ship, unlike the service-role key, which must never reach the APK.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun supabaseProperty(key: String, env: String): String =
    System.getenv(env) ?: localProperties.getProperty(key) ?: ""

android {
    namespace = "com.wirekey"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wirekey"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.1-cloudsync"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${supabaseProperty("supabase.url", "SUPABASE_URL")}\""
        )
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"${supabaseProperty("supabase.anonKey", "SUPABASE_ANON_KEY")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        // Carries the Supabase URL/anon key read above into com.wirekey.BuildConfig.
        buildConfig = true
    }
    // composeOptions is no longer needed with kotlin.plugin.compose in Kotlin 2.0+
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    
    // Compose dependencies
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")
    
    // Datastore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ── Supabase cloud sync (auth + PostgREST + Realtime) ────────────────────
    // Pinned to the 2.6.x line on purpose: it is the newest release still built
    // against Kotlin 2.0 / compileSdk 34 / Java 8. supabase-kt 3.x pulls
    // kotlin-stdlib 2.4, androidx 1.10 and Ktor 3.5, which would force a Kotlin,
    // compileSdk and jvmTarget upgrade across the whole app.
    implementation(platform("io.github.jan-tennert.supabase:bom:2.6.1"))
    implementation("io.github.jan-tennert.supabase:gotrue-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    // Ktor engine for the above. OkHttp rather than the `android` engine because
    // Realtime needs WebSocket support, which the android engine does not have.
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    // Raised over the 1.5.1 that supabase-kt 2.6.1's own graph resolves to: the Kotlin 2.0
    // serialization compiler plugin is only guaranteed against 1.6.x runtimes, and Ktor
    // 2.3.12 is built against this same version.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
