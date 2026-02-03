package com.stockkeeper.exception;

/**
 * Thrown when a reservation state transition is not allowed.
 *
 * Examples:
 *   - Attempting COMMIT on a reservation that is LOADED (must be HELD)
 *   - Attempting LOAD on a reservation that is HELD (must be COMMITTED)
 *   - Concurrent modification changed the state between read and write
 *
 * Maps to HTTP 409 Conflict.
 */
public class InvalidTransitionException extends RuntimeException {

    public InvalidTransitionException(String message) {
        super(message);
    }
}
