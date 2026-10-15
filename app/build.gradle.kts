import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// AdMob ids. Debug builds always use Google's official TEST ids. Release builds read the real ids
// from Gradle properties (-PADMOB_APP_ID=... or ~/.gradle/gradle.properties) or from the untracked
// local.properties, and fall back to the test ids with a warning.
val admobTestIds = mapOf(
    "ADMOB_APP_ID" to "ca-app-pub-3940256099942544~3347511713",
    "ADMOB_BANNER_ID" to "ca-app-pub-3940256099942544/9214589741",
    "ADMOB_INTERSTITIAL_ID" to "ca-app-pub-3940256099942544/1033173712",
)
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val onlyDebugTasks = gradle.startParameter.taskNames.let { tasks ->
    tasks.isNotEmpty() && tasks.all { it.contains("debug", ignoreCase = true) }
}

fun releaseAdmobId(key: String): String {
    val configured = (providers.gradleProperty(key).orNull ?: localProperties.getProperty(key))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (configured != null) return configured
    if (!onlyDebugTasks) {
        logger.warn("WARNING: $key is not set (Gradle property or local.properties); the release build uses the AdMob TEST id.")
    }
    return admobTestIds.getValue(key)
}

android {
    namespace = "com.enjoyyourtime.globalfitness"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.enjoyyourtime.globalfitness"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            manifestPlaceholders["admobAppId"] = admobTestIds.getValue("ADMOB_APP_ID")
            buildConfigField("String", "ADMOB_BANNER_ID", "\"${admobTestIds.getValue("ADMOB_BANNER_ID")}\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"${admobTestIds.getValue("ADMOB_INTERSTITIAL_ID")}\"")
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["admobAppId"] = releaseAdmobId("ADMOB_APP_ID")
            buildConfigField("String", "ADMOB_BANNER_ID", "\"${releaseAdmobId("ADMOB_BANNER_ID")}\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"${releaseAdmobId("ADMOB_INTERSTITIAL_ID")}\"")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The UI is forced to Spanish at runtime; keep all languages in any bundle build.
    bundle {
        language {
            enableSplit = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Optional Google account + cloud backup, ads (with GDPR consent) and the "remove ads" purchase.
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.ads)
    implementation(libs.ump)
    implementation(libs.billing.ktx)

    testImplementation(libs.junit)
}
