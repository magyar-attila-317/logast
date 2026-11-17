package com.example.e2e.weekly;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TargetWeekResolver {
    private static final Logger log = LoggerFactory.getLogger(TargetWeekResolver.class);
    private String targetWeek;

    @PostConstruct
    void init() {
        targetWeek = String.valueOf(LocalDate.now().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        log.info("Target week resolved [targetWeek={}]", targetWeek);
    }

    public String forCountry(String countryCode) {
        log.info("Target week checked");
        return targetWeek;
    }

    public String targetWeek() {
        return targetWeek;
    }
}
