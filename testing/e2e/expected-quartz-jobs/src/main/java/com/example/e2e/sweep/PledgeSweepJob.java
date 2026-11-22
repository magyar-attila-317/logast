package com.example.e2e.sweep;

import com.example.e2e.logging.ObservedContext;
import com.example.e2e.logging.TraceContextEnv;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.SchedulerException;

/** Sweeps one region's expired pledges, then has them audited. */
@Slf4j
public class PledgeSweepJob implements Job {
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        ObservedContext.entrypoint(SweepMetrics.PLEDGE_SWEEP_JOB_EXECUTE)
                .remoteParent(TraceContextEnv.carrier(context.getMergedJobDataMap()))
                .mdc("jobName", context.getJobDetail().getKey().getName())
                .mdc("region", context.getMergedJobDataMap().get("region"))
                .run(() -> {
                    log.info("Pledge sweep started"); // logast:wording
                    context.put(TraceContextEnv.KEY, TraceContextEnv.current());
                    String region = context.getMergedJobDataMap().getString("region");
                    SweepLedger.sweep(region);
                    JobDataMap next = new JobDataMap();
                    next.put("region", region);
                    try {
                        next.putAll(TraceContextEnv.current());
                        context.getScheduler().triggerJob(new JobKey("pledgeAudit"), next);
                    } catch (SchedulerException e) {
                        throw new JobExecutionException();
                    }
                });
    }
}
