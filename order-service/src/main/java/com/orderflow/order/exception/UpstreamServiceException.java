package com.orderflow.order.exception;

/** Thrown when a required upstream service (e.g. Product Service) is unreachable or errors. */
public class UpstreamServiceException extends RuntimeException {

    public UpstreamServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
