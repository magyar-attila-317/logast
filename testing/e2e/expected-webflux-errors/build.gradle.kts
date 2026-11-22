plugins { java }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework:spring-jdbc")
    implementation("io.micrometer:micrometer-observation")
    implementation("io.micrometer:context-propagation")
}
