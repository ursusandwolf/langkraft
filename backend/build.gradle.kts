plugins {
    kotlin("jvm")
    id("io.ktor.plugin") version "2.3.5"
}

dependencies {
    implementation(project(":shared")) // Access shared models
    implementation("io.ktor:ktor-server-core:2.3.5")
    implementation("io.ktor:ktor-server-netty:2.3.5")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.5")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.5")
    
    // YtdlpJava from user repository
    implementation("com.github.ursusandwolf:YtdlpJava:dev") 
}
