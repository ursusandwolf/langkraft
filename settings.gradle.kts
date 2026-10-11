pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "langkraft"
include(":core", ":shared", ":backend", ":webApp")
if (!providers.gradleProperty("skipAndroid").getOrElse("false").toBoolean()) {
    include(":androidApp")
}