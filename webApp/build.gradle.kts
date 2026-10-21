plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
}

compose.experimental {
    web.application {}
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "langkraft.js"
            }
        }
        binaries.executable()
    }
    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
                implementation("io.ktor:ktor-client-js:2.3.5")
            }
        }
    }
}
