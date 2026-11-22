package dev.logast.capture;

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureTest {
    @TempDir
    Path tmp;

    @Test
    void writesOneJsonLinePerEventWithCallerAndMdcAndSurvivesReset() throws Exception {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Path file = tmp.resolve("logs.jsonl");
        CaptureAppender appender = new CaptureAppender(file);
        appender.setContext(ctx);
        appender.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        Logger log = LoggerFactory.getLogger(CaptureTest.class);
        MDC.put("order.id", "o-1");
        try {
            log.info("Charge \"card\" failed [order.id={}]", "o-1");
        } finally {
            MDC.clear();
            ctx.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
            appender.stop();
        }
        var lines = Files.readAllLines(file);
        String line = lines.get(lines.size() - 1);
        assertThat(line).contains("\"pattern\":\"Charge \\\"card\\\" failed [order.id={}]\"")
                .contains("\"message\":\"Charge \\\"card\\\" failed [order.id=o-1]\"")
                .contains("\"callerClass\":\"dev.logast.capture.CaptureTest\"")
                .contains("\"mdc\":{\"order.id\":\"o-1\"}");
    }
}
