import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
    id("org.jetbrains.kotlin.plugin.noarg") version "1.8.22"
    alias(libs.plugins.protobuf)
}

/**
 * The same version stamp the two firmwares carry, computed the same way.
 *
 * Mirrors `Scripts/GenVersion.sh` in the Locator and Receiver repos deliberately,
 * down to the shape of the string: `YYYY.MM.DD-<git describe>` with `.HHMMSS`
 * appended when the tree is dirty. The point is that the three versions a user can
 * read off one screen each — app here, locator and receiver on their settings
 * screens — are directly comparable, and that "which of these three is behind?"
 * is answerable by looking at them.
 *
 * The dirty suffix exists for the reason the firmware script records at length: a
 * dirty tree describes IDENTICALLY for as long as it stays dirty, so without the
 * time of day every build made between two commits carries the same stamp and the
 * version stops identifying the build. The time buys uniqueness, which is the only
 * property a development build needs. A clean build gets no suffix, and that
 * absence is the signal that the stamp names an actual commit.
 *
 * The build DATE, not the commit date — again matching the firmware, where the
 * question being answered is "when was this thing built".
 *
 * Runs at configuration time, so it is re-evaluated on every configuration rather
 * than cached in a task output. Falls back to "unknown" rather than failing the
 * build: git missing, or a source drop that is not a repo, must not stop the app
 * compiling. (Note this is not configuration-cache friendly; the project does not
 * enable it. If that changes, this wants a ValueSource.)
 */
fun gitVersionStamp(): String {
    fun git(vararg args: String): String? = try {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(rootDir)
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.bufferedReader().readText().trim()
        process.errorStream.close()
        if (process.waitFor() == 0 && out.isNotEmpty()) out else null
    } catch (e: Exception) {
        null
    }

    // --always so a repo with no tags still yields the short hash rather than
    // failing; --long so tags, once they exist, carry their distance.
    val describe = git("describe", "--tags", "--long", "--dirty", "--always") ?: return "unknown"
    val now = LocalDateTime.now()
    val stamp = now.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) + "-" + describe
    return if (describe.endsWith("-dirty")) {
        stamp + "." + now.format(DateTimeFormatter.ofPattern("HHmmss"))
    } else {
        stamp
    }
}

android {
    namespace = "com.steampigeon.flightmanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.steampigeon.flightmanager"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // What versionName cannot answer: WHICH build is on this phone. The two
        // firmwares have carried a git stamp for a while and the app had nothing,
        // so of the three halves of the system the one being changed most often was
        // the only one that could not identify itself — which matters most across a
        // breaking wire change, where the question is exactly "are these two in
        // step?".
        buildConfigField("String", "GIT_VERSION", "\"${gitVersionStamp()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // OFF DELIBERATELY — this is not a flag waiting to be flipped.
            //
            // R8 would have to be told to keep three things this app reaches
            // reflectively or through JNI, and proguard-rules.pro is still the
            // commented-out template:
            //   - full protobuf-java (NOT lite), which backs the DataStore
            //     settings store — the closest-zoom setting, launch sites, the
            //     locator password
            //   - jSerialComm, which loads a native library
            //   - MapLibre, whose Java/JNI boundary is wide
            //
            // The failure mode is what makes this worth a comment rather than an
            // attempt: R8 problems appear at RUNTIME, not build time. A release
            // that compiles clean and then silently fails to deserialize settings,
            // discovered at a launch site, is a worse outcome than a 66 MB APK.
            // Validating it means signing a release build — there is no signing
            // config here, `assembleRelease` produces an unsigned APK — and then
            // smoke-testing settings persistence, BLE connect, map render and
            // flight-profile transfer.
            //
            // Debug logging does NOT depend on this. It is gated at the source in
            // SpLog, on BuildConfig.DEBUG, so nothing prints from a release build
            // either way. What minification would additionally buy is removing the
            // log strings from the APK — they are still readable to anyone who
            // unpacks it — plus the size reduction.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // FlightDataRepository logs on every received packet, and SpLog is live
            // on debug builds — which is what a unit test compiles against. Without
            // this, the first android.util.Log call throws "not mocked" and the
            // whole flight-data transfer layer is untestable.
            isReturnDefaultValues = true
        }
    }
}

secrets {
    // To add your Maps API key to this project:
    // 1. If the secrets.properties file does not exist, create it in the same folder as the local.properties file.
    // 2. Add this line, where YOUR_API_KEY is your API key:
    //        MAPS_API_KEY=YOUR_API_KEY
    propertiesFileName = "secrets.properties"

    // A properties file containing default secret values. This file can be
    // checked in version control.
    defaultPropertiesFileName = "local.defaults.properties"

    // Configure which keys should be ignored by the plugin by providing regular expressions.
    // "sdk.dir" is ignored by default.
    ignoreList.add("keyToIgnore") // Ignore the key "keyToIgnore"
    ignoreList.add("sdk.*")       // Ignore all keys matching the regexp "sdk.*"
}

dependencies {

    implementation(libs.androidx.bluetooth)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.text.google.fonts)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    // Google Maps SDK removed — the map is MapLibre now (see ui/MapLibreCompat.kt).
    // play-services-location stays: it provides the fused location provider.
    implementation (libs.play.services.location)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.i18n)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.accompanist.permissions)
    implementation(libs.kotlinx.coroutines.android)
//    implementation(libs.ui)
//    implementation(libs.androidx.material)
    implementation(libs.androidx.core.ktx.v190)
    implementation(libs.play.services.nearby)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx.v287)
    implementation(libs.androidx.runtime.livedata)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.java.util)
    implementation(libs.protobuf.kotlin)
    // jSerialComm / usb-serial-for-android removed with the unused USB-serial path.
    implementation(libs.androidx.material.icons.extended)
    // PROTOTYPE: MapLibre offline-satellite evaluation (see prototype/maplibre-offline)
    implementation(libs.maplibre.android)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.27.0"
    }
    // Generates the java Protobuf-lite code for the Protobufs in this project. See
    // https://github.com/google/protobuf-gradle-plugin#customizing-protobuf-compilation
    // for more information.
    generateProtoTasks {
        // see https://github.com/google/protobuf-gradle-plugin/issues/518
        // see https://github.com/google/protobuf-gradle-plugin/issues/491
        // all() here because of android multi-variant
        all().forEach { task ->
            // this only works on version 3.8+ that has buildins for javalite / kotlin lite
            // with previous version the java build in is to be removed and a new plugin
            // need to be declared
            task.builtins {
                create("java") {
                    option("lite")
                }
                create("kotlin") {
                    option("lite")
                }
            }
        }
    }
}