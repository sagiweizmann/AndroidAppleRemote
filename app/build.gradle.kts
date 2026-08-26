import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Release signing. CI passes these through the environment; locally they come from
// keystore.properties, which is gitignored along with the keystore itself. With neither
// present, release builds simply stay unsigned and debug builds are unaffected.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

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

    signingConfigs {
        create("release") {
            val path = signingValue("storeFile", "KEYSTORE_FILE")
            if (path != null && file(path).exists()) {
                storeFile = file(path)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true   // allows signing-key rotation later
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Leave the build unsigned rather than failing when no key is configured.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
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
