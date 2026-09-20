import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "net.raphaelgf11.ilo3manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.raphaelgf11.ilo3manager"
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0-beta1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
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
            if (keystoreProperties.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    }

    // One APK per architecture rather than a single universal one: the WireGuard tunnel needs a
    // native library compiled from Go, which would otherwise be bundled several times over in a
    // download most devices only need one copy of.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += "META-INF/versions/*/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.jsch)
    implementation(libs.androidx.biometric)
    // biometric 1.1.0 drags in androidx.fragment 1.2.5, whose FragmentActivity rejects any
    // requestCode above 16 bits. The Compose ActivityResult APIs always generate codes above
    // 65536, so every rememberLauncherForActivityResult in the app crashed on launch. Fragment
    // 1.3+ dropped that check for the ActivityResult APIs; pin a current version explicitly.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.bouncycastle.tls)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.nanohttpd)
    // ZXing rather than ML Kit: no Google Play Services dependency, which keeps the app usable
    // on devices without them and avoids pulling a proprietary component into an open project.
    implementation(libs.zxing.embedded)
    // Built from ./wgtunnel by gomobile (see wgtunnel/README.md). Checked in as a binary because
    // rebuilding it would otherwise require Go and the Android NDK just to compile the app.
    implementation(files("libs/wgtunnel.aar"))
    // Only for obtaining a Drive token. The Drive REST calls are made directly rather than with
    // the Google API client library, which would pull a large dependency tree for three requests.
    implementation(libs.play.services.auth)
    testImplementation(libs.junit)
    // The real org.json, since the Android one is a stub in unit tests and the backup format is
    // JSON that genuinely needs testing.
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}