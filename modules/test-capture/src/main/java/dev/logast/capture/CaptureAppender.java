package dev.logast.capture;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Writes every log event as one JSON line: logger, level, raw pattern, rendered message, MDC, caller, test. */
final class CaptureAppender extends AppenderBase<ILoggingEvent> {
    static final String NAME = "logast-capture";
    private final BufferedWriter out;

    CaptureAppender(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        setName(NAME);
    }

    @Override
    protected synchronized void append(ILoggingEvent e) {
        StringBuilder sb = new StringBuilder(256).append('{');
        field(sb, "logger", e.getLoggerName()).append(',');
        field(sb, "level", e.getLevel().toString()).append(',');
        field(sb, "pattern", e.getMessage()).append(',');
        field(sb, "message", e.getFormattedMessage()).append(',');
        field(sb, "thread", e.getThreadName()).append(',');
        field(sb, "test", TestTracker.current()).append(',');
        StackTraceElement[] caller = e.getCallerData();
        if (caller != null && caller.length > 0) {
            field(sb, "callerClass", caller[0].getClassName()).append(',');
            sb.append("\"callerLine\":").append(caller[0].getLineNumber()).append(',');
        }
        IThrowableProxy t = e.getThrowableProxy();
        if (t != null) {
            field(sb, "throwable", t.getClassName()).append(',');
        }
        sb.append("\"mdc\":{");
        boolean first = true;
        for (Map.Entry<String, String> m : e.getMDCPropertyMap().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            field(sb, m.getKey(), m.getValue());
        }
        sb.append("}}\n");
        try {
            out.write(sb.toString());
            out.flush();
        } catch (IOException ignored) {
            // capture is best effort; never fail the tests
        }
    }

    private static StringBuilder field(StringBuilder sb, String key, String value) {
        str(sb, key).append(':');
        return value == null ? sb.append("null") : str(sb, value);
    }

    private static StringBuilder str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"');
    }

    @Override
    public void stop() {
        super.stop();
        try {
            out.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
