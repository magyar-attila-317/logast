package com.example.e2e.loggers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class BaseJob {
    protected final Logger log = LoggerFactory.getLogger(getClass());

    abstract void run(Runnable step);
}
