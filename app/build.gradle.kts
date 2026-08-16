import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing lives outside the repo (gitignored keystore.properties + .jks) -- generate
// both with `keytool -genkeypair` per the README before running assembleRelease. Absent
// entirely when only building debug, which is why this is loaded conditionally rather than
// required.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.espaillat.healthsync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.espaillat.healthsync"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-ktx:1.13.0")

    // Health Connect
    implementation("androidx.health.connect:connect-client:1.1.0")

    // On-device PDF text extraction for the Samsung Health Monitor blood-pressure import (its
    // BpContentProvider is signature|privileged-locked, confirmed via a direct SecurityException
    // querying it -- a manual PDF export/share is the only route in). The export is a small,
    // machine-generated PDF with real embedded text, not a scan, so text extraction is both
    // simpler and more accurate than OCR here -- no misread-digit risk on top of the parsing
    // itself. Android has no built-in PDF text-extraction API (PdfRenderer only rasterizes).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Background work (widget-triggered sync)
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Google Drive v3 via service-account auth
    implementation("com.google.api-client:google-api-client:2.9.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20260720-2.0.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.50.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.http-client:google-http-client-gson:1.47.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
