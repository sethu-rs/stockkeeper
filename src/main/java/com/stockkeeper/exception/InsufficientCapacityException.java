package com.stockkeeper.exception;

/**
 * Thrown when a stock operation fails because available capacity
 * cannot satisfy the requested quantity.
 *
 * Maps to HTTP 409 Conflict — the request is valid but the current
 * state of the resource prevents it from being fulfilled.
 */
public class InsufficientCapacityException extends RuntimeException {

    public InsufficientCapacityException(String message) {
        super(message);
    }
}
