package com.example.e2e.intake;

import com.example.e2e.order.Order;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

/** Spring Cloud Stream binds the functions spring.cloud.function.definition names. */
@Configuration
public class IntakeFunctions {
    @Bean
    public Consumer<Message<Order>> orderIntake(IntakeDesk intake) {
        return message -> intake.accept(message.getPayload());
    }

    @Bean
    public Function<String, String> pledgeNormalizer(CodeNormalizer normalizer) {
        return normalizer::normalize;
    }

    @Bean
    public Consumer<String> pledgeArchive(CodeNormalizer normalizer) {
        return normalizer::archive;
    }

    @Bean
    public Supplier<String> heartbeat() {
        return () -> "alive";
    }
}
