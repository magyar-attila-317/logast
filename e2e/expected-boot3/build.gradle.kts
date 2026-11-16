plugins {
    java
    id("org.springframework.boot") version "3.5.7"
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.7"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework:spring-jdbc")
    implementation("io.micrometer:micrometer-observation")
    implementation("io.micrometer:context-propagation")
}
