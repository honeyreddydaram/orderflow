package com.orderflow.common.error;

/** Thrown when a requested entity does not exist; mapped to HTTP 404 by each service's advice. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
