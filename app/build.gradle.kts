plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    kotlin("plugin.serialization") version "2.1.10"
}

val releaseKeystorePath = System.getenv("KEYSTORE_FILE")
val releaseKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
val releaseKeystoreAlias = System.getenv("KEYSTORE_ALIAS")
val releaseKeyPassword = System.getenv("KEY_PASSWORD") ?: releaseKeystorePassword
val releaseKeystoreFile = releaseKeystorePath?.let { rootProject.file(it) }
val canSignRelease = releaseKeystoreFile?.isFile == true &&
    !releaseKeystorePassword.isNullOrBlank() &&
    !releaseKeystoreAlias.isNullOrBlank()

android {
    namespace = "ShiggyXposed.xposed"
    compileSdk = 36

    defaultConfig {
        applicationId = "ShiggyXposed.xposed"
        minSdk = 24
        targetSdk = 36
        versionCode = 1373
        versionName = "1.4.0"
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystoreFile!!
                storePassword = releaseKeystorePassword!!
                keyAlias = releaseKeystoreAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
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
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
    }
}

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.ktor:ktor-client-core:3.3.1")
    implementation("io.ktor:ktor-client-cio:3.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("dev.rushii:libunbound:1.0.0")
}
