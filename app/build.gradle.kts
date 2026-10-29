import java.util.zip.CRC32

// AGP 9 built-in Kotlin: no org.jetbrains.kotlin.android plugin here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Output of a git command in this project, or null when git is missing or fails. */
fun git(vararg args: String): String? = runCatching {
    val result = providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
        isIgnoreExitValue = true
    }
    if (result.result.get().exitValue != 0) null else result.standardOutput.asText.get().trim()
}.getOrNull()

/** Short commit of the working tree, "-dirty" when it has uncommitted changes; "unknown" without git. */
val gitCommit: String = git("rev-parse", "--short", "HEAD")?.takeIf { it.isNotEmpty() }
    ?.let { sha -> if (git("status", "--porcelain").isNullOrEmpty()) sha else "$sha-dirty" }
    ?: "unknown"

/**
 * `-Pprototype`: the mechanics prototype build. It ships app/src/prototype/assets/levels.json (made by
 * tools/prototype_pack.py: three levels per mechanic, each block in its own theme) as the campaign, unlocks every
 * level, and installs next to the main app (application id suffix ".proto", name "Critter Café Proto"). The APK is
 * app/build/outputs/apk/<type>/app-proto-<type>.apk.
 */
val prototype: Boolean = providers.gradleProperty("prototype").isPresent

if (prototype) base.archivesName = "app-proto"

/** CRC32 (8 hex digits) of the shipped campaign, so analytics can tell level-pack builds apart. */
val packHash: String = providers.fileContents(
    layout.projectDirectory.file(if (prototype) "src/prototype/assets/levels.json" else "src/main/assets/levels.json"),
)
    .asBytes.orNull
    ?.let { bytes -> CRC32().apply { update(bytes) }.value.toString(16).padStart(8, '0') }
    ?: "unknown"

android {
    namespace = "com.phloots.crittercafe"
    // Current AndroidX/Compose (core 1.19, compose 1.12) require compileSdk >= 37. AGP 9 DSL for minor API levels:
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.phloots.crittercafe"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        // GameAnalytics keys from the environment (Infisical project critter_cafe:
        // `infisical run --domain https://infisical.app.phloots.com/api --projectId fc6e386a-... --env prod -- ./gradlew`,
        // see README).
        // Without them the build still works and analytics is off.
        for (key in listOf("GAMEANALYTICS_GAME_KEY", "GAMEANALYTICS_SECRET_KEY")) {
            buildConfigField("String", key, "\"${System.getenv(key).orEmpty()}\"")
        }
        // Analytics custom fields: build = versionName-commit[-dirty], pack = CRC32 of levels.json.
        buildConfigField("String", "BUILD_ID", "\"$versionName-$gitCommit\"")
        buildConfigField("String", "PACK_HASH", "\"$packHash\"")
        buildConfigField("boolean", "UNLOCK_ALL", "$prototype")
        if (prototype) {
            applicationIdSuffix = ".proto"
            versionNameSuffix = "-proto"
        }
    }

    if (prototype) {
        // the build types' source sets override main: levels.json replaces the campaign, app_name the name
        for (type in listOf("debug", "release")) {
            sourceSets.getByName(type).assets.directories.add("src/prototype/assets")
            sourceSets.getByName(type).res.directories.add("src/prototype/res") // app_name
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gameanalytics)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
