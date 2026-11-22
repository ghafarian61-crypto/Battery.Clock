plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.batterypercent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.batterypercent"
        minSdk = 26
        targetSdk = 33
        versionCode = 11
        versionName = "3.6.1"
    }

    // One fixed key for every build (the file is part of the project). Without it each GitHub run makes a new random
    // debug key, and Android refuses to install the new APK over the old one, which means uninstalling and losing
    // all settings. With it, updates install over the old version and keep the settings.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { isDebuggable = true }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
