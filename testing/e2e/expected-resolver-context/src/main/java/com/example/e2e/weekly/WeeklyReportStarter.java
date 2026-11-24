package com.example.e2e.weekly;

import com.example.e2e.logging.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class WeeklyReportStarter {
    private static final Logger log = LoggerFactory.getLogger(WeeklyReportStarter.class);
    private final TargetWeekResolver targetWeekResolver;

    public WeeklyReportStarter(TargetWeekResolver targetWeekResolver) {
        this.targetWeekResolver = targetWeekResolver;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try (var mdc = MdcScope.of("targetWeek", targetWeekResolver.targetWeek())) {
            log.info("Weekly report started");
        }
    }
}
