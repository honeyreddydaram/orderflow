package com.orderflow.common.error;

/**
 * Thrown when a request is well-formed but violates a domain rule (e.g. insufficient stock,
 * order already in a terminal state). Mapped to HTTP 409 by each service's advice.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
