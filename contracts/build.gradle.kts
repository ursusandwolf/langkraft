plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()
    js(IR) {
        browser { testTask { useKarma { useChromeHeadless() } } }
    }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
