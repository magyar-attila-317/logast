plugins {
    `java-library`
    `maven-publish`
}

java { withSourcesJar() }

// Added to a service's testRuntimeClasspath by `logast verify` (init script); keep it dependency-free.
dependencies {
    compileOnly(platform(libs.spring.boot.bom))
    compileOnly("ch.qos.logback:logback-classic")
    compileOnly(platform(libs.junit.bom))
    compileOnly(libs.junit.launcher)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("ch.qos.logback:logback-classic")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.launcher)
    testImplementation(libs.assertj)
}

tasks.withType<JavaCompile>().configureEach { options.release = 17 }
tasks.test { useJUnitPlatform() }
