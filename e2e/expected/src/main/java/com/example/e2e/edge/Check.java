package com.example.e2e.edge;

import java.util.Optional;

/** A generic check: implementations override test(T) with a concrete type. */
public interface Check<T> {
    Optional<String> test(T value);
}
