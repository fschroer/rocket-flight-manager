plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
    id("org.jetbrains.kotlin.plugin.noarg") version "1.8.22"
}

android {
    namespace = "com.steampigeon.flightmanager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.steampigeon.flightmanager"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    implementation("androidx.bluetooth:bluetooth:1.0.0-alpha02")
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
    // Google Maps Compose library
    val mapsComposeVersion = "4.4.1"
    implementation("com.google.maps.android:maps-compose:$mapsComposeVersion")
    // Google Maps Compose utility library
    implementation("com.google.maps.android:maps-compose-utils:$mapsComposeVersion")
    // Google Maps Compose widgets library
    implementation("com.google.maps.android:maps-compose-widgets:$mapsComposeVersion")
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    //implementation ("com.google.android.libraries.places:places:4.1.0")
    //implementation ("com.google.maps.android:places-ktx:3.2.0")
    implementation ("com.google.android.gms:play-services-location:21.3.0")
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation("com.mutualmobile:composesensors:1.2.0")
    implementation("com.google.accompanist:accompanist-permissions:0.21.1-beta")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.compose.ui:ui:1.3.0")
    implementation("androidx.compose.material:material:1.3.0")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("com.google.android.gms:play-services-nearby:18.0.2")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.runtime:runtime-livedata:1.7.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
}