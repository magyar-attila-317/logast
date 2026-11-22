// Copied by logast (support.mode: vendor) from logging-support. Keep in sync or switch to the starter.
// Source: logast, https://github.com/magyar-attila-317/logast (Apache License 2.0)
package com.example.shipping.logging;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Registers {@link MdcHygieneFilter} before every other filter in servlet applications, when
 * {@code logging-support.mdc-hygiene.enabled} is {@code true} (off by default: the starter alone changes nothing).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "logging-support.mdc-hygiene.enabled", havingValue = "true")
public class MdcHygieneConfiguration {

    @Bean
    public FilterRegistrationBean<MdcHygieneFilter> mdcHygieneFilter() {
        FilterRegistrationBean<MdcHygieneFilter> registration = new FilterRegistrationBean<>(new MdcHygieneFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("mdcHygieneFilter");
        return registration;
    }
}
