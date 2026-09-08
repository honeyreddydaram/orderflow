package com.orderflow.order.exception;

/**
 * Thrown when a concurrent request with the same Idempotency-Key is still being processed and
 * hasn't resolved within the bounded wait - see IdempotencyKeyService.
 */
public class IdempotencyKeyInProgressException extends RuntimeException {

    public IdempotencyKeyInProgressException(String message) {
        super(message);
    }
}
