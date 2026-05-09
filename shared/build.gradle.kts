plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("app.cash.sqldelight")
}

kotlin {
    androidTarget()
    jvm("desktop") // For testing on desktop
    js(IR) {
        browser()
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("app.cash.sqldelight:runtime:2.0.0")
                implementation("io.ktor:ktor-client-core:2.3.5")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.media3:media3-exoplayer:1.1.1")
                implementation("app.cash.sqldelight:android-driver:2.0.0")
            }
        }
        val jsMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:web-worker-driver:2.0.0")
            }
        }
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.langkraft.db")
        }
    }
}
