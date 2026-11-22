package com.example.e2e.parcel;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.retry.annotation.EnableRetry;

@Configuration
@EnableResilientMethods
@EnableRetry
public class ParcelConfig {
}
