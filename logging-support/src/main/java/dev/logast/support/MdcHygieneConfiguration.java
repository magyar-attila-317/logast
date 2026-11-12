package dev.logast.support;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/** Registers {@link MdcHygieneFilter} before every other filter in servlet applications. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "logging-support.mdc-hygiene.enabled", matchIfMissing = true)
public class MdcHygieneConfiguration {

    @Bean
    public FilterRegistrationBean<MdcHygieneFilter> mdcHygieneFilter() {
        FilterRegistrationBean<MdcHygieneFilter> registration = new FilterRegistrationBean<>(new MdcHygieneFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("mdcHygieneFilter");
        return registration;
    }
}
