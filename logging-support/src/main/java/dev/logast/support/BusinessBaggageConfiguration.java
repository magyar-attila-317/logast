package dev.logast.support;

import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Connects {@link BusinessBaggage} to the application's Tracer ({@code Tracer.createBaggageInScope}, Micrometer Tracing
 * 1.x). Changes nothing by itself: only code that calls {@code BusinessBaggage.with} creates baggage.
 */
@AutoConfiguration(afterName = "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration")
@ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
public class BusinessBaggageConfiguration {

    @Bean
    public SmartInitializingSingleton businessBaggageInitializer(ObjectProvider<Tracer> tracer) {
        return () -> {
            Tracer t = tracer.getIfAvailable();
            if (t == null) {
                return;
            }
            BusinessBaggage.opener((key, value) -> {
                BaggageInScope scope = t.createBaggageInScope(key, value);
                return scope::close;
            });
        };
    }
}
