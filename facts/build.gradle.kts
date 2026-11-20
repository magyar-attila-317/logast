plugins {
    `java-library`
    `maven-publish`   // core depends on it, and the Gradle plugin publishes core (#115)
}

description = "logast facts: the format of code and deployment facts (#152)"

dependencies {
    api(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
