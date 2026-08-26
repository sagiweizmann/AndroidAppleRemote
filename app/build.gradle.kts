import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.sagi.appleremotebridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sagi.appleremotebridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"

        // Chaquopy needs a host ("build") Python matching the version above. Android
        // Studio's Gradle daemon doesn't inherit a shell PATH, so point at it directly
        // when we can find it. Override with -Pchaquopy.buildPython=/path/to/python3.11
        // (or a chaquopy.buildPython entry in ~/.gradle/gradle.properties).
        val explicitBuildPython = (findProperty("chaquopy.buildPython") as String?)
            ?: sequenceOf(
                "/opt/homebrew/bin/python3.11",
                "/usr/local/bin/python3.11",
            ).firstOrNull { File(it).canExecute() }
        if (explicitBuildPython != null) {
            buildPython(explicitBuildPython)
        }

        pip {
            install("cryptography==42.0.8")
            install("srptools>=1.0")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
