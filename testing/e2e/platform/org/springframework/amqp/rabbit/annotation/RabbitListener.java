package org.springframework.amqp.rabbit.annotation;

import java.lang.annotation.*;

/** Stand-in for spring-rabbit's annotation (only javac gets it; logast recognises it by name, as without the jar). */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RabbitListener {
    String[] queues() default {};

    String ackMode() default "";
}
