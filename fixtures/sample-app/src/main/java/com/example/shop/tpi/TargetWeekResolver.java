package com.example.shop.tpi;

import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.IsoFields;

@Component
public class TargetWeekResolver {
    private String targetWeek;

    @PostConstruct
    void init() {
        targetWeek = String.valueOf(LocalDate.now().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        MDC.put("targetWeek", targetWeek); // expect: MDC_NOT_OWNER
    }

    public String targetWeek() {
        return targetWeek;
    }
}
