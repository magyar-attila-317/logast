package com.example.shop.settlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** Batch job: owns the run's context, but sets it by hand and wipes everything at the end. */
@Component
public class SettlementJob implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(SettlementJob.class);
    private final CountryCodeResolver countryCodeResolver;
    private final TargetWeekResolver targetWeekResolver;

    public SettlementJob(CountryCodeResolver countryCodeResolver, TargetWeekResolver targetWeekResolver) {
        this.countryCodeResolver = countryCodeResolver;
        this.targetWeekResolver = targetWeekResolver;
    }

    @Override
    public void run(String... args) {
        MDC.put("countryCode", countryCodeResolver.countryCode()); // expect: MDC_NOT_CLOSEABLE, MDC_CLEAR, MDC_KEY_ALIAS
        MDC.put("targetWeek", targetWeekResolver.targetWeek());
        log.info("Settlement export started");
        export();
        log.info("Settlement export completed"); // expect: METRIC_OUTCOME_UNMEASURED
        MDC.clear();
    }

    private void export() {
        log.debug("Settlement rows written");
    }
}
