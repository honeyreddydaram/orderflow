package com.orderflow.inventory.exception;

/** Thrown when POST /inventory targets a product that already has a stock row. */
public class StockAlreadyExistsException extends RuntimeException {

    public StockAlreadyExistsException(String message) {
        super(message);
    }
}
