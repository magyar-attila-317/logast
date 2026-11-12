package com.example.shop.tpi;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves a value once at startup, and (wrongly) puts it into MDC itself. */
@Component
public class CountryCodeResolver {
    private final String countryCode;

    public CountryCodeResolver(@Value("${tpi.country:HU}") String countryCode) {
        this.countryCode = countryCode;
        MDC.put("countryCode", countryCode); // expect: MDC_NOT_OWNER
    }

    public String countryCode() {
        return countryCode;
    }
}
