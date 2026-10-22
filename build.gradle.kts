plugins {
    kotlin("multiplatform") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("app.cash.sqldelight") version "2.0.2" apply false
    id("com.android.library") version "8.4.0" apply false
}

// Repositories are now managed in settings.gradle.kts

// Installed Node and explicit local package inputs prevent stale browser storage code.
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    extensions.configure<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension> {
        download = false
    }
    tasks.withType<org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask>().configureEach {
        inputs.files(fileTree("shared/src/jsMain/storage") {
            include("*.js", "package.json")
        })
        // Yarn classic otherwise reuses a stale copy of file: dependencies.
        args.add("--force")
    }
}
