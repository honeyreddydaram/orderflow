package com.orderflow.common.error;

import java.time.Instant;
import java.util.List;

/**
 * Uniform error response body returned by every service's {@code @ControllerAdvice}.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> fieldErrors) {

    public record FieldViolation(String field, String message) {
    }

    public static ApiError of(int status, String error, String message, String path, String correlationId) {
        return new ApiError(Instant.now(), status, error, message, path, correlationId, List.of());
    }

    public static ApiError withFieldErrors(
            int status, String error, String message, String path, String correlationId,
            List<FieldViolation> fieldErrors) {
        return new ApiError(Instant.now(), status, error, message, path, correlationId, fieldErrors);
    }
}
