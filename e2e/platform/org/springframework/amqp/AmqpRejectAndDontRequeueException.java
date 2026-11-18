package org.springframework.amqp;

/** Stand-in for spring-amqp's exception (only javac gets it; logast sees it by name, as without the jar). */
public class AmqpRejectAndDontRequeueException extends RuntimeException {
    public AmqpRejectAndDontRequeueException(String message) {
        super(message);
    }
}
