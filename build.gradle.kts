plugins {
    kotlin("multiplatform") version "1.9.20" apply false
    kotlin("jvm") version "1.9.20" apply false
    id("org.jetbrains.compose") version "1.5.10" apply false
    id("app.cash.sqldelight") version "2.0.0" apply false
    id("com.android.library") version "8.1.0" apply false
}

// Repositories are now managed in settings.gradle.kts

