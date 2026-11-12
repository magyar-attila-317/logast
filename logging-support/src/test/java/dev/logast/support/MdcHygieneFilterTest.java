package dev.logast.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class MdcHygieneFilterTest {
    private final ListAppender<ILoggingEvent> warnings = new ListAppender<>();

    @BeforeEach
    void capture() {
        MdcHygiene.resetForTests();
        warnings.start();
        ((Logger) LoggerFactory.getLogger(MdcHygiene.class)).addAppender(warnings);
    }

    @AfterEach
    void release() {
        ((Logger) LoggerFactory.getLogger(MdcHygiene.class)).detachAppender(warnings);
        MDC.clear();
    }

    private void request(Runnable inside) throws Exception {
        new MdcHygieneFilter().doFilter(new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(),
                (req, res) -> inside.run());
    }

    @Test
    void requestStartsCleanAndEndsClean() throws Exception {
        MDC.put("order.id", "leaked-by-previous-task");
        MDC.put("traceId", "abc");
        request(() -> {
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
            MDC.put("tenant.id", "t-1"); // forgotten
        });
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        assertThat(warnings.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "MDC entries leaked past earlier work on this request thread [keys=[order.id]]; scope them with MdcScope (try-with-resources)",
                "MDC entries leaked past an HTTP request [keys=[tenant.id]]; scope them with MdcScope (try-with-resources)");
    }

    @Test
    void sameLeakIsReportedOnce() throws Exception {
        for (int i = 0; i < 5; i++) {
            request(() -> MDC.put("tenant.id", "t-1"));
        }
        assertThat(warnings.list).hasSize(1);
    }

    @Test
    void scopedCodeProducesNoWarning() throws Exception {
        request(() -> {
            try (var mdc = MdcScope.of("order.id", "o-1")) {
                assertThat(MDC.get("order.id")).isEqualTo("o-1");
            }
        });
        assertThat(warnings.list).isEmpty();
    }

    @Test
    void registeredFirstInServletApps() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(MdcHygieneConfiguration.class))
                .run(ctx -> assertThat(ctx.getBean(FilterRegistrationBean.class).getOrder()).isEqualTo(Integer.MIN_VALUE));
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(MdcHygieneConfiguration.class))
                .withPropertyValues("logging-support.mdc-hygiene.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FilterRegistrationBean.class));
    }
}
