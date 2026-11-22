rootProject.name = "logast"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
// #127: the settings' repositories (with what init scripts add) for gradle/publishing.gradle: a project cannot reach them
gradle.extra["logastSettingsRepositories"] = listOf(dependencyResolutionManagement.repositories, pluginManagement.repositories)

include("facts", "kb", "core", "cli", "gradle-plugin", "logging-support", "test-capture", "fixtures:sample-app")
// the modules live in modules/, the test projects in testing/: the project names (and so every task path, :cli:e2e …) stay
for (name in listOf("facts", "kb", "core", "cli", "gradle-plugin", "logging-support", "test-capture")) {
    project(":$name").projectDir = file("modules/$name")
}
project(":fixtures").projectDir = file("testing/fixtures")
project(":fixtures:sample-app").projectDir = file("testing/fixtures/sample-app")
