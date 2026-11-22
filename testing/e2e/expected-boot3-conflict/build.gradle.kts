plugins {
    java
    id("org.springframework.boot") version "3.5.7"
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.7"))
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")
    implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework:spring-jdbc")
    implementation("io.micrometer:micrometer-observation")
    implementation("io.micrometer:context-propagation")
}

tasks.withType<JavaExec>().configureEach { jvmArgs("-javaagent:/opt/opentelemetry-javaagent.jar") }
