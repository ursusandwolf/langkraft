plugins {
    kotlin("android")
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.langkraft.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.langkraft.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.compose.material:material:1.9.3")
}