plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "family.remote.parent"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.kinpilot.parent"
        minSdk = 31
        targetSdk = 35
        versionCode = System.getenv("KINPILOT_VERSION_CODE")?.toIntOrNull() ?: 5
        versionName = System.getenv("KINPILOT_VERSION_NAME") ?: "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("distribution") {
            System.getenv("KINPILOT_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = System.getenv("KINPILOT_STORE_PASSWORD")
            keyAlias = System.getenv("KINPILOT_KEY_ALIAS")
            keyPassword = System.getenv("KINPILOT_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("distribution")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    kotlinOptions { jvmTarget = "21" }
}
dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation(project(":protocol"))
    implementation("com.google.zxing:core:3.5.3")
    // google-webrtc:1.0.32006 was removed from Google Maven; using the
    // community-maintained stream-webrtc-android which ships the same org.webrtc package.
    implementation("io.getstream:stream-webrtc-android:1.0.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui-viewbinding:1.6.8")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")
}
