package com.example.e2e.job;

/** One implementation is active per profile; the executor runs it once at startup. */
public abstract class TpiJob {
    public void execute() {
        doExecute();
    }

    protected abstract void doExecute();
}
