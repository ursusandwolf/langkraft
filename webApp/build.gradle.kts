plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    js(IR) {
        browser()
        binaries.executable()
    }
    
    tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest> {
        enabled = false
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation("io.ktor:ktor-http:2.3.5")
                implementation(compose.runtime)
                implementation(compose.ui)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation("io.insert-koin:koin-core:3.5.0")
            }
        }
    }
}
