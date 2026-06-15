pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.springframework.boot") version "3.3.6"
        id("io.spring.dependency-management") version "1.1.7"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "streaming-backend"
include(
    "discovery-service",
    "gateway-service",
    "auth-service",
    "stream-service",
    "chat-service",
    "notification-service",
)
