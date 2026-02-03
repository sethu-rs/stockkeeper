package com.stockkeeper.exception;

/**
 * Thrown when a CapacityStock item is not found for the given pk/sk.
 *
 * Maps to HTTP 404 Not Found.
 */
public class StockNotFoundException extends RuntimeException {

    public StockNotFoundException(String pk, String sk) {
        super("Stock not found: pk=" + pk + ", sk=" + sk);
    }
}
