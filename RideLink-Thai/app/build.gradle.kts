import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingFile = rootProject.file("signing/local-signing.properties")
check(signingFile.isFile) { "กรุณาเรียก scripts/Build.ps1 เพื่อเตรียมกุญแจลงนามของ RideLinkไทย" }
val localSigning = Properties().apply {
    signingFile.inputStream().use(::load)
}

android {
    // คง namespace ของซอร์สที่ใช้ซ้ำ ส่วน package ที่ติดตั้งเป็นแอปใหม่
    namespace = "dev.zanderp.opencfmoto"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "app.ridelink.thai"
        minSdk = 29
        targetSdk = 34
        versionCode = 7
        versionName = "0.2.5"
        manifestPlaceholders["appLabel"] = "RideLinkไทย"
        buildConfigField("String", "GIT_HASH", "\"ridelink-thai\"")
    }

    signingConfigs {
        create("ridelinkLocal") {
            storeFile = rootProject.file("signing/ridelink-local.jks")
            storePassword = localSigning.getProperty("storePassword")
            keyAlias = localSigning.getProperty("keyAlias")
            keyPassword = localSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ridelinkLocal")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ridelinkLocal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    testImplementation("junit:junit:4.13.2")
}
