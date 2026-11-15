package net.javacrumbs.shedlock.spring.annotation;

import java.lang.annotation.*;

/** Stand-in for ShedLock's annotation (only javac gets it; logast recognises it by name, as without the jar). */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SchedulerLock {
    String name();
}
