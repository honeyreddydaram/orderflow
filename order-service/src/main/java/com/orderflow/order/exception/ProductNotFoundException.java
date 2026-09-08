package com.orderflow.order.exception;

/** Thrown when a referenced product id doesn't exist (or is inactive) in Product Service. */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String message) {
        super(message);
    }
}
