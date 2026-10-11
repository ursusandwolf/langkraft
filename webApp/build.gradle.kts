plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "langkraft.js"
                devServer = (devServer ?: org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig.DevServer()).apply {
                    port = 8081
                }
            }
        }
        binaries.executable()
    }
    sourceSets {
        jsMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.material)
            implementation(compose.ui)
            implementation(npm("sql.js", "1.13.0"))
            implementation(devNpm("copy-webpack-plugin", "12.0.2"))
        }
    }
}