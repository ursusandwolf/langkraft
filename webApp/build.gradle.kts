plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Kotlin's local npm dependency also contains the standalone recovery entry.
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack>().configureEach {
    inputs.dir(project(":shared").layout.projectDirectory.dir("src/jsMain/storage"))
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
