plugins {
    kotlin("jvm")
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("com.langkraft.backend.ApplicationKt") }
dependencies {
    implementation(project(":core"))
    implementation("io.ktor:ktor-server-netty:3.3.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.3.0")
    implementation("io.ktor:ktor-server-status-pages:3.3.0")
    implementation("io.ktor:ktor-server-partial-content:3.3.0")
    implementation("io.ktor:ktor-server-cors:3.3.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test-junit5"))
    testImplementation("io.ktor:ktor-server-test-host:3.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
