plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fengeon.resprite_desktop"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fengeon.resprite_desktop"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.28.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("foss") {
            storeFile = file("../keystore/mcpresprite.keystore")
            storePassword = System.getenv("RESPRITE_KEYSTORE_PASS") ?: "mcpresprite"
            keyAlias = System.getenv("RESPRITE_KEY_ALIAS") ?: "mcpresprite"
            keyPassword = System.getenv("RESPRITE_KEY_PASS") ?: "mcpresprite"
        }
        // CI rebuilds the debug APK on every push. A stock debug build makes each
        // runner generate its own debug keystore, so installing the new APK over
        // the old one failed with INSTALL_FAILED_UPDATE_INCOMPATIBLE and forced a
        // full uninstall every time. This committed key keeps updates installable.
        create("debugStable") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "mcpresprite-debug"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("foss")
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            // Fall back to the stock generated debug key if the committed keystore
            // is missing (e.g. someone pruned it), so the build never breaks.
            if (file("../keystore/debug.keystore").exists()) {
                signingConfig = signingConfigs.getByName("debugStable")
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
        compose = true
        viewBinding = true
    }

    packaging {
        // cloudflared (the Cloudflare Tunnel client) ships as a native lib so it
        // can be executed straight from nativeLibraryDir. Android 10+ blocks
        // executing files from the app's data dirs (W^X), and legacy packaging is
        // what makes AGP extract native libs with the exec bit set.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("androidx.exifinterface:exifinterface:1.3.7")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
