import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Le trousseau de la variante release vient de l'environnement. Sans ces variables, la
// variante release se construit sans signature.
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

val releaseKeystore = env("DICTEE_KEYSTORE")
val releaseStorePassword = env("DICTEE_KEYSTORE_PASSWORD")
val releaseKeyAlias = env("DICTEE_KEY_ALIAS")
val releaseKeyPassword = env("DICTEE_KEY_PASSWORD") ?: releaseStorePassword

android {
    namespace = "top.supergeoff.dictee"
    compileSdk = 35

    defaultConfig {
        applicationId = "top.supergeoff.dictee"
        minSdk = 31
        targetSdk = 35
        versionCode = 11
        versionName = "0.2.4"
    }

    signingConfigs {
        if (releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        } else if (releaseKeystore != null) {
            logger.warn("DICTEE_KEYSTORE est défini sans DICTEE_KEYSTORE_PASSWORD ou DICTEE_KEY_ALIAS : la variante release n'est pas signée.")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("com.google.android.gms:play-services-auth:22.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20260814")
}
