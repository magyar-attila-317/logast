package com.example.e2e;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties(RetrySettings.class)
@SpringBootApplication
public class E2eApplication {
    public static void main(String[] args) {
        SpringApplication.run(E2eApplication.class, args);
    }
}
