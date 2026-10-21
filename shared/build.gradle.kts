plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("app.cash.sqldelight")
}

val androidEnabled = providers.gradleProperty("enableAndroid").orNull == "true"
if (androidEnabled) apply(plugin = "com.android.library")

kotlin {
    if (androidEnabled) androidTarget()
    jvm("desktop") // For testing on desktop
    js(IR) {
        browser {
            testTask { useKarma { useChromeHeadless() } }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
                implementation("app.cash.sqldelight:runtime:2.0.0")
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.0")
                implementation("app.cash.sqldelight:async-extensions:2.0.0")
                implementation("io.ktor:ktor-client-core:2.3.5")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
                implementation("io.ktor:ktor-client-mock:2.3.5")
            }
        }
        val jvmMain by creating {
            dependsOn(commonMain)
        }
        val desktopMain by getting {
            dependsOn(jvmMain)
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.0.0")
                implementation("io.ktor:ktor-client-cio:2.3.5")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.0.0")
            }
        }
        if (androidEnabled) {
            val androidMain by getting {
                dependsOn(jvmMain)
                dependencies {
                    implementation("androidx.media3:media3-exoplayer:1.1.1")
                    implementation("app.cash.sqldelight:android-driver:2.0.0")
                    implementation("io.ktor:ktor-client-android:2.3.5")
                }
            }
        }
        val jsMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-js:2.3.5")
                implementation(npm("@langkraft/web-storage", project.file("src/jsMain/storage")))
            }
        }
    }
}

if (androidEnabled) {
    extensions.getByName("android").withGroovyBuilder {
        setProperty("namespace", "com.langkraft.shared")
        setProperty("compileSdk", 34)
        "defaultConfig" { setProperty("minSdk", 24) }
        "compileOptions" {
            setProperty("sourceCompatibility", JavaVersion.VERSION_1_8)
            setProperty("targetCompatibility", JavaVersion.VERSION_1_8)
        }
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
