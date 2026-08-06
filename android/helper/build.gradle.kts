plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "family.remote.helper"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.kinpilot.helper"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    kotlinOptions { jvmTarget = "21" }
}
dependencies {
    implementation(project(":protocol"))
    // google-webrtc:1.0.32006 was removed from Google Maven; using the
    // community-maintained stream-webrtc-android which ships the same org.webrtc package.
    implementation("io.getstream:stream-webrtc-android:1.0.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui-viewbinding:1.6.8")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")
}
