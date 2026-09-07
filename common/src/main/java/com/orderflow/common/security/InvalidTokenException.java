package com.orderflow.common.security;

/** Thrown when a JWT fails signature verification, is expired, or is otherwise malformed. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
