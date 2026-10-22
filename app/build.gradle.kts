plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // The Compose compiler plugin has to be applied to the module that
    // enables compose, not just declared in the root project.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.amethyst2213.operatorchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.amethyst2213.operatorchat"
        minSdk = 26
        targetSdk = 35
        versionCode = 63
        versionName = "3.1"

        // Release uses the strict HTTPS-only network config; debug allows the
        // dev server cleartext carve-out.
        manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config_debug"
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
        compose = true
    }

    testOptions {
        unitTests {
            // The Compose screens are rendered for real in the tests, so the
            // resources have to be there.
            isIncludeAndroidResources = true
        }
    }

    composeOptions {
        // The Compose compiler ships with the Kotlin plugin for Kotlin 2.x,
        // so there is no separate compiler version to pin.
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("org.json:json:20240303")
    // In-chat video playback (message attachments) and EXIF handling for
    // client-side image compression before upload.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Live video broadcast (operator -> MediaMTX via RTMP).
    implementation("com.github.pedroSG94.RootEncoder:library:2.6.1")
    implementation("com.github.pedroSG94.RootEncoder:extra-sources:2.6.1")
    // The operator app was rebuilt on Jetpack Compose for v3.0: the XML
    // layouts could not express the operator dashboards (per-chat controls,
    // the live console, the schedule screen) without turning into a wall of
    // nested LinearLayouts, and the chat thread was rebuilt from scratch on
    // every incoming message because the row list was regenerated in the
    // adapter.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Robolectric renders the real Compose tree, so the screen can be
    // measured (the reply field used to measure ZERO width, which is why
    // no reply could be typed) and screenshotted.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
