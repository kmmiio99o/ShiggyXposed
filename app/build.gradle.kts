plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    kotlin("plugin.serialization") version "2.1.10"
}

android {
    namespace = "GoonXposed.xposed"
    compileSdk = 36

    defaultConfig {
        applicationId = "GoonXposed.xposed"
        minSdk = 22
        targetSdk = 36
        versionCode = 1040
        versionName = "1.0.4"
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

repositories { 
    mavenCentral()
    google()
    maven("https://jitpack.io") 
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.0")
    implementation("io.ktor:ktor-client-core:3.3.0")
    implementation("io.ktor:ktor-client-cio:3.3.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
