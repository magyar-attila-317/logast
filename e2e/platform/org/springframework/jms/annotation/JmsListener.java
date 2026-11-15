package org.springframework.jms.annotation;

import java.lang.annotation.*;

/** Stand-in for spring-jms's annotation (only javac gets it; logast recognises it by name, as without the jar). */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JmsListener {
    String destination();
}
