plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    kotlin("plugin.serialization") version "2.1.10"
}

android {
    namespace = "cocobo1.pupu.xposed"
    compileSdk = 36

    defaultConfig {
        applicationId = "cocobo1.pupu.xposed"
        minSdk = 22
        targetSdk = 36
        versionCode = 1000
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("io.ktor:ktor-client-core:3.1.1")
    implementation("io.ktor:ktor-client-cio:3.1.1")
}
