package com.example.e2e.weekly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CountryCodeResolver {
    private static final Logger log = LoggerFactory.getLogger(CountryCodeResolver.class);
    private final String countryCode;

    public CountryCodeResolver(@Value("${weekly.country:HU}") String countryCode) {
        this.countryCode = countryCode;
        log.info("Country code resolved [countryCode={}]", countryCode);
    }

    public String countryCode() {
        return countryCode;
    }
}
