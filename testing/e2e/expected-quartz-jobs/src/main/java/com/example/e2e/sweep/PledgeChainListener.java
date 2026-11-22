package com.example.e2e.sweep;

import com.example.e2e.logging.TraceContextEnv;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.JobListener;
import org.quartz.SchedulerException;

/** After the sweep, the report: the chain's next step. */
public class PledgeChainListener implements JobListener {
    @Override
    public String getName() {
        return "pledgeChain";
    }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {
    }

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        try {
            context.getScheduler().triggerJob(new JobKey("pledgeReport"), new JobDataMap(TraceContextEnv.from(context.get(TraceContextEnv.KEY))));
        } catch (SchedulerException e) {
            throw new IllegalStateException(e);
        }
    }
}
