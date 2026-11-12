package com.example.shop.messaging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Custom entrypoint annotation, registered via logast.yaml entrypoints.extraAnnotations. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface InboxHandler {
}
