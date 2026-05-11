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

    implementation("io.ktor:ktor-client-core:2.3.5")
    implementation("io.ktor:ktor-client-cio:2.3.5")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.5")
    implementation("io.ktor:ktor-client-logging:2.3.5")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // YtdlpJava from user repository
    implementation("com.github.ursusandwolf:YtdlpJava:dev") 

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
}

tasks.test {
    useJUnitPlatform()
}
