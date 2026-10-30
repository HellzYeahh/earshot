import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Release signing, one key per distribution (docs/release-signing.md). The values come from
 * keystore.properties in the repo root (gitignored) as `<flavor>.storeFile`, `.storePassword`,
 * `.keyAlias`, `.keyPassword`, or from EARSHOT_<FLAVOR>_STORE_FILE etc. (CI). Without them a release
 * build comes out unsigned: never debug-signed, so a debug-key APK can't be published by mistake.
 * The one exception is the opt-in legacy bridge below.
 */
val signingProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun signingValue(flavor: String, key: String): String? =
    signingProps.getProperty("$flavor.$key")
        ?: System.getenv("EARSHOT_${flavor.uppercase()}_" + key.replace(Regex("([A-Z])"), "_\$1").uppercase())

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/*
 * The one-time bridge for the pre-1.0 GitHub installs (docs/release-signing.md, "Moving existing
 * users"). Only with -Pearshot.legacyBridge=true: the foss flavour keeps the old app ID dev.earshot
 * and its release build is signed with this PC's debug key, the key every release up to 0.9.0 was
 * signed with. That gives one last GitHub build (0.9.1) that updates those installs in place and
 * brings them Back up and restore. It is still the foss flavour, so Distribution says GitHub and
 * there's no store code. Without the property nothing changes; never set it for any other build.
 */
val legacyBridge = providers.gradleProperty("earshot.legacyBridge").orNull == "true"
if (legacyBridge) {
    // quiet, not warn: the documented command runs with -q, which hides warnings.
    logger.quiet("earshot.legacyBridge: the foss flavour is dev.earshot and its release is debug-signed (the 0.9.1 bridge only)")
}

android {
    namespace = "dev.earshot"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.earshot"
        minSdk = 28 // DynamicsProcessing (the pro EQ) needs Android 9+
        targetSdk = 37
        versionCode = 10
        versionName = "0.9.0"
    }

    signingConfigs {
        for (flavor in listOf("play", "foss")) {
            val storeFile = signingValue(flavor, "storeFile") ?: continue
            create("${flavor}Release") {
                this.storeFile = rootProject.file(storeFile)
                storePassword = signingValue(flavor, "storePassword")
                keyAlias = signingValue(flavor, "keyAlias")
                keyPassword = signingValue(flavor, "keyPassword")
            }
        }
    }

    /*
     * Two distributions from one codebase (docs/roadmap.md, 1.0 plan). Code for one of them only
     * lives in app/src/play/ or app/src/foss/, so the GitHub APK never carries store-only code.
     * Debug builds keep the debug key: playDebug is dev.earshot, so it updates the debug-signed
     * builds already on the developer's phone.
     */
    flavorDimensions += "distribution"
    productFlavors {
        // Google Play. The store holds the app-signing key; we sign uploads with the upload key.
        create("play") {
            dimension = "distribution"
            signingConfig = signingConfigs.findByName("playRelease")
        }
        // GitHub, F-Droid, IzzyOnDroid: everything free. Its own app ID, so it can't clash with the
        // Play install's signature. The legacy bridge (above) is the one build without it.
        create("foss") {
            dimension = "distribution"
            if (legacyBridge) {
                // Release only: the debug build type already uses the debug key.
                signingConfig = signingConfigs.getByName("debug")
            } else {
                applicationIdSuffix = ".foss"
                signingConfig = signingConfigs.findByName("fossRelease")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true // ICodecService, the Shizuku user service interface
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":dsp"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Shizuku is in the GitHub build only (B8, docs/research/play-readiness.md): its code is in
    // src/foss/, the Google Play build gets the stubs in src/play/. No type-safe accessor exists for a
    // flavour's configuration in the Kotlin DSL, hence the quoted name.
    "fossImplementation"(libs.shizuku.api)
    "fossImplementation"(libs.shizuku.provider)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
