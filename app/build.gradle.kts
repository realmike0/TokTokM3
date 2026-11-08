plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.example.toktok"
    compileSdk = 36
    defaultConfig { applicationId = "com.example.toktok"; minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3:1.5.0-alpha17")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    val m3 = "1.5.1"; val cx = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$m3"); implementation("androidx.media3:media3-ui:$m3")
    implementation("androidx.media3:media3-transformer:$m3"); implementation("androidx.media3:media3-effect:$m3"); implementation("androidx.media3:media3-common:$m3")
    implementation("androidx.camera:camera-core:$cx"); implementation("androidx.camera:camera-camera2:$cx")
    implementation("androidx.camera:camera-lifecycle:$cx"); implementation("androidx.camera:camera-video:$cx"); implementation("androidx.camera:camera-view:$cx"); implementation("androidx.camera:camera-extensions:$cx")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
