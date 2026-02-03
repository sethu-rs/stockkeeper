package com.stockkeeper.exception;

/**
 * Thrown when a reservation lookup by reservation_id returns no results.
 */
public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(String reservationId) {
        super("Reservation not found: " + reservationId);
    }
}
