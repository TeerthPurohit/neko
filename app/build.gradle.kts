plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services") apply false
}
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
android {
    namespace = "dev.neko.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.neko.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.3.0"
        val backendUrl = rootProject.file(".env").takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("NEKO_BACKEND_URL=") }?.substringAfter('=')?.trim().orEmpty()
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"" + backendUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Reads the text of PDF bank statements on the phone; nothing is uploaded.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
