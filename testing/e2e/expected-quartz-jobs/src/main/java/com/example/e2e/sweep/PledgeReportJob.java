package com.example.e2e.sweep;

import com.example.e2e.logging.ObservedContext;
import com.example.e2e.logging.TraceContextEnv;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

/** Reports the pledges of the last days. */
@Slf4j
public class PledgeReportJob extends QuartzJobBean {
    static final String DAYS = "days";

    @Override
    protected void executeInternal(JobExecutionContext context) {
        ObservedContext.entrypoint(SweepMetrics.PLEDGE_REPORT_JOB_EXECUTE_INTERNAL)
                .remoteParent(TraceContextEnv.carrier(context.getMergedJobDataMap()))
                .mdc("jobName", context.getJobDetail().getKey().getName())
                .mdc("days", context.getMergedJobDataMap().get("days"))
                .run(() -> {
                    log.info("Pledge report started"); // logast:wording
                    context.put(TraceContextEnv.KEY, TraceContextEnv.current());
                    JobDataMap data = context.getMergedJobDataMap();
                    SweepLedger.report(data.getIntValue(DAYS));
                });
    }
}
