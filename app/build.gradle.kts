plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "uk.co.mypina.admin"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.co.mypina.admin"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing comes from the environment (CI secrets, or your shell; see README).
    // Without KEYSTORE_FILE the release build is unsigned and cannot be installed.
    val keystoreFile = System.getenv("KEYSTORE_FILE")
    if (keystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "pina-admin"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Dev server on the Mac, reached from the phone via `adb reverse tcp:3000 tcp:3000`.
            buildConfigField("String", "ADMIN_BASE_URL", "\"http://localhost:3000\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "ADMIN_BASE_URL", "\"https://admin.mypina.co.uk\"")
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }

    testOptions {
        // AdminApi's default cookie provider references CookieManager (the tests pass their own);
        // return android.* stub defaults instead of throwing, rather than pulling in Robolectric.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Vendored ntag424-java jar (built from a pinned commit, see README).
    implementation(fileTree("libs") { include("*.jar") })

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
