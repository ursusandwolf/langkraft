plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.sqldelight")
    kotlin("plugin.serialization") version "2.0.21"
}

/*
android {
    namespace = "com.langkraft"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
}
*/

kotlin {
    // androidTarget()
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
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")
                implementation("app.cash.sqldelight:runtime:2.0.0")
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.0")
                implementation("io.ktor:ktor-client-core:2.3.5")
                implementation("io.ktor:ktor-client-logging:2.3.5")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.5")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.5")

                // Koin DI
                val koinVersion = "3.5.0"
                implementation("io.insert-koin:koin-core:$koinVersion")
                implementation("io.insert-koin:koin-compose:1.1.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            }
        }
        /*
        val androidMain by getting {
            dependencies {
                implementation("androidx.media3:media3-exoplayer:1.1.1")
                implementation("app.cash.sqldelight:android-driver:2.0.0")
            }
        }
        */
        val desktopMain by getting {
            val osName = System.getProperty("os.name").lowercase()
            val targetOs = when {
                osName.contains("mac") -> "mac"
                osName.contains("win") -> "win"
                else -> "linux"
            }
            
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("app.cash.sqldelight:sqlite-driver:2.0.0")
                implementation("io.ktor:ktor-client-cio:2.3.5")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
                
                // VLCj for robust audio playback
                implementation("uk.co.caprica:vlcj:4.8.0")
                
                // Lanterna TUI
                implementation("com.googlecode.lanterna:lanterna:3.1.2")
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

compose.desktop {
    application {
        mainClass = "com.langkraft.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageName = "Langkraft"
            packageVersion = "1.0.0"
        }
    }
}

tasks.register<JavaExec>("runTui") {
    group = "application"
    mainClass.set("com.langkraft.CliMainKt")
    val desktopJvm = kotlin.targets.getByName<org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget>("desktop")
    classpath = desktopJvm.compilations["main"].output.allOutputs + desktopJvm.compilations["main"].runtimeDependencyFiles
    standardInput = System.`in`
    
    // Pass JavaFX native library path to JVM
    val javafxJars = classpath.filter { it.name.contains("javafx") }
    if (!javafxJars.isEmpty) {
        val javafxDir = javafxJars.first().parent
        systemProperty("java.library.path", javafxDir)
    }
}
