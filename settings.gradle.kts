rootProject.name = "logast"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include("core", "cli", "gradle-plugin", "logging-support", "test-capture", "fixtures:sample-app")
