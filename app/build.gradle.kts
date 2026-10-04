import java.util.Properties

/**
 * API keys are read from `local.properties`, which is gitignored, so they never reach the repo.
 * They become BuildConfig defaults; the in-app Settings screen can override them at runtime without
 * a rebuild. See the README for the Google Cloud setup steps.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secretKey(name: String): String =
    localProperties.getProperty(name) ?: System.getenv(name) ?: ""

val googleApiKey = secretKey("google.api.key")
val googleTtsApiKey = secretKey("google.tts.apiKey").ifBlank { googleApiKey }
val googleGeminiApiKey = secretKey("google.gemini.apiKey").ifBlank { googleApiKey }

/**
 * The Maps SDK reads its key from the manifest, so unlike the TTS and Gemini keys this one cannot be
 * supplied from the in-app Settings screen: it has to exist when the app is built. A blank value is
 * the normal case and simply means the app draws its OpenStreetMap map instead.
 */
val googleMapsApiKey = secretKey("google.maps.apiKey").ifBlank { googleApiKey }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.walkingtours.app"
    compileSdk = 37

    // The Google Maps SDK's legacy renderer still links against org.apache.http, which Android
    // removed from the platform for apps targeting API 28 and above. This app targets 37, and the
    // newer renderer is not available on every device — the SDK quietly falls back to the legacy one
    // (MapsInitializer reports "preferredRenderer: LATEST" then "loadedRenderer: LEGACY"), at which
    // point creating a map crashed the process with:
    //
    //     NoClassDefFoundError: Failed resolution of: Lorg/apache/http/ProtocolVersion;
    //
    // Declaring the platform's optional legacy library makes those classes resolvable again.
    useLibrary("org.apache.http.legacy")

    defaultConfig {
        applicationId = "com.walkingtours.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GOOGLE_TTS_API_KEY", "\"$googleTtsApiKey\"")
        buildConfigField("String", "GOOGLE_GEMINI_API_KEY", "\"$googleGeminiApiKey\"")
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"$googleMapsApiKey\"")
        manifestPlaceholders["googleMapsApiKey"] = googleMapsApiKey
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.core)
    implementation(libs.androidx.compose.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.osmdroid.android)
    implementation(libs.maps.compose)
    implementation(libs.androidx.fragment)
}
