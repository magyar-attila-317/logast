package dev.logast.capture;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/** Which test is running: events are grouped per test to find context lost within one flow. */
final class TestTracker implements TestExecutionListener {
    private static volatile String current;

    static String current() {
        return current;
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        if (id.isTest()) {
            current = id.getUniqueId();
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (id.isTest()) {
            current = null;
        }
    }
}
