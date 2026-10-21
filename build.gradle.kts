buildscript {
    if (providers.gradleProperty("enableAndroid").orNull == "true") {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools.build:gradle:8.2.2") }
    }
}

plugins {
    kotlin("multiplatform") version "1.9.20" apply false
    kotlin("jvm") version "1.9.20" apply false
    kotlin("plugin.serialization") version "1.9.20" apply false
    id("org.jetbrains.compose") version "1.5.10" apply false
    id("app.cash.sqldelight") version "2.0.0" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

// Use the installed Node.js for Web checks; Android tooling stays opt-in.
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    extensions.configure<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension> {
        download = false
    }
    tasks.withType<org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask>().configureEach {
        inputs.files(fileTree("shared/src/jsMain/storage") {
            include("index.js", "database.worker.js", "package.json")
        })
        // Yarn classic otherwise reuses a stale copy of file: dependencies.
        args.add("--force")
    }
}
