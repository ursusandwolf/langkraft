import com.android.build.gradle.LibraryExtension

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.sqldelight")
}
val androidEnabled = !providers.gradleProperty("skipAndroid").getOrElse("false").toBoolean()
if (androidEnabled) apply(plugin = "com.android.library")

kotlin {
    if (androidEnabled) androidTarget()
    jvm()
    js(IR) { browser() }
    jvmToolchain(21)
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            api("app.cash.sqldelight:runtime:2.1.0")
            implementation("app.cash.sqldelight:async-extensions:2.1.0")
            implementation("app.cash.sqldelight:coroutines-extensions:2.1.0")
            implementation("io.ktor:ktor-client-core:3.3.0")
            implementation("io.ktor:ktor-client-content-negotiation:3.3.0")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        jvmMain.dependencies {
            implementation("app.cash.sqldelight:sqlite-driver:2.1.0")
            implementation("io.ktor:ktor-client-cio:3.3.0")
        }
        if (androidEnabled) getByName("androidMain").dependencies {
            implementation("androidx.media3:media3-exoplayer:1.5.1")
            implementation("app.cash.sqldelight:android-driver:2.1.0")
            implementation("io.ktor:ktor-client-okhttp:3.3.0")
        }
        jsMain.dependencies {
            implementation("app.cash.sqldelight:web-worker-driver:2.1.0")
            implementation("io.ktor:ktor-client-js:3.3.0")
        }
    }
}
if (androidEnabled) extensions.configure<LibraryExtension> {
    namespace = "com.langkraft.shared"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.langkraft.db")
            generateAsync.set(true)
        }
    }
}