package com.example.shop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ClientConfig {
    @Bean
    public RestTemplate invoiceRestTemplate() {
        return new RestTemplate(); // expect: HTTP_CLIENT_UNTRACED
    }
}
