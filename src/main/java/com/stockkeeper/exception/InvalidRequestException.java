package com.stockkeeper.exception;

import java.util.List;

/**
 * Thrown when a request fails config-driven validation (before DynamoDB access).
 *
 * Carries a list of human-readable error messages so the caller knows exactly
 * which fields or rules were violated.
 */
public class InvalidRequestException extends RuntimeException {

    private final List<String> errors;

    public InvalidRequestException(String message) {
        super(message);
        this.errors = List.of(message);
    }

    public InvalidRequestException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
