plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

android {
    namespace   = "com.shadowself"
    compileSdk  = 34

    defaultConfig {
        applicationId  = "com.shadowself"
        minSdk         = 26
        targetSdk      = 34
        versionCode    = 1
        versionName    = "1.0.0"
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    kotlinOptions { jvmTarget = "17" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-work:1.1.0")
    ksp("androidx.hilt:hilt-compiler:1.1.0")

    // Room DB
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Security / Crypto
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // TFLite (on-device ML)
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    // Required for gradient ops (ReluGrad, BroadcastGradientArgs) in the train signature
    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.14.0")

    // Location
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // Firebase (FCM push alerts)
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // CameraX (silent intruder selfie)
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Gson (JSON serialisation for Room converters)
    implementation("com.google.code.gson:gson:2.10.1")

    // Google Auth (for FCM HTTP v1)
    implementation("com.google.auth:google-auth-library-oauth2-http:1.19.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
