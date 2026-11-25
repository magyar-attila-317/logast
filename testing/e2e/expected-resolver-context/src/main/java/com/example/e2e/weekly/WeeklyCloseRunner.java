package com.example.e2e.weekly;

import com.example.e2e.logging.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class WeeklyCloseRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(WeeklyCloseRunner.class);
    private final CountryCodeResolver countryCodeResolver;
    private final TargetWeekResolver targetWeekResolver;

    public WeeklyCloseRunner(CountryCodeResolver countryCodeResolver, TargetWeekResolver targetWeekResolver) {
        this.countryCodeResolver = countryCodeResolver;
        this.targetWeekResolver = targetWeekResolver;
    }

    @Override
    public void run(String... args) {
        try (var mdc = MdcScope.of("countryCode", countryCodeResolver.countryCode(), "targetWeek", targetWeekResolver.targetWeek())) {
            log.info("Weekly close started");
            close();
            log.info("Weekly close completed");
            WeeklyMetrics.weeklyCloseCompleted();
        }
    }

    private void close() {
        log.debug("Weekly close rows written [week={}]", targetWeekResolver.forCountry(countryCodeResolver.countryCode()));
    }
}
