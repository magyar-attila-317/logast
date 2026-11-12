package dev.logast.capture;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.core.spi.ContextAwareBase;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Registered through ServiceLoader: attaches {@link CaptureAppender} to the root logger for the whole test run
 * and re-attaches it whenever Logback is reset (Spring Boot reconfigures logging for every test context).
 * Output: {@code -Dlogast.capture.file} (default {@code build/logast/test-logs.jsonl}).
 */
public final class LogCaptureSessionListener implements LauncherSessionListener {
    private CaptureAppender appender;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        session.getLauncher().registerTestExecutionListeners(new TestTracker());
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) {
            return; // not Logback: nothing to capture
        }
        try {
            appender = new CaptureAppender(Path.of(System.getProperty("logast.capture.file", "build/logast/test-logs.jsonl")));
            appender.setContext(ctx);
            appender.start();
            attach(ctx);
            ctx.addListener(new Reattach(ctx));
        } catch (Exception e) {
            System.err.println("logast capture disabled: " + e);
        }
    }

    private void attach(LoggerContext ctx) {
        Logger root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(CaptureAppender.NAME) == null) {
            root.addAppender(appender);
        }
        // Capture DEBUG too where the service's own config allows it; never force levels.
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        if (appender != null) {
            appender.stop();
        }
    }

    private final class Reattach extends ContextAwareBase implements LoggerContextListener {
        private final LoggerContext ctx;

        Reattach(LoggerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public boolean isResetResistant() {
            return true;
        }

        @Override
        public void onStart(LoggerContext context) {
            attach(ctx);
        }

        @Override
        public void onReset(LoggerContext context) {
            attach(ctx);
        }

        @Override
        public void onStop(LoggerContext context) {
        }

        @Override
        public void onLevelChange(Logger logger, ch.qos.logback.classic.Level level) {
        }
    }
}
