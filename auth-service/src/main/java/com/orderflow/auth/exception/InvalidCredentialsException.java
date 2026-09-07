package com.orderflow.auth.exception;

/** Thrown when a login attempt's username/password combination does not match. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
