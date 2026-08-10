plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
    id("org.jetbrains.kotlin.plugin.noarg") version "1.8.22"
    alias(libs.plugins.protobuf)
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