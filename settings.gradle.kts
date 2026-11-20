rootProject.name = "logast"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
// #127: the settings' repositories (with what init scripts add) for gradle/publishing.gradle: a project cannot reach them
gradle.extra["logastSettingsRepositories"] = listOf(dependencyResolutionManagement.repositories, pluginManagement.repositories)

include("facts", "core", "cli", "gradle-plugin", "logging-support", "test-capture", "fixtures:sample-app")
