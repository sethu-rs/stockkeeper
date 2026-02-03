package com.stockkeeper.model.dto;

import com.stockkeeper.model.Reservation;

/**
 * Response DTO for GET /reservations/{reservationId} and for the reservation
 * data embedded in every stock-operation response.
 *
 * Maps 1:1 from the {@link Reservation} domain model. Exposed as a record
 * for immutability and concise JSON serialization.
 */
public record ReservationResponse(
        String reservationId,
        String shipmentId,
        String capacityType,
        String stockPk,
        String stockSk,
        int quantity,
        String classFlag,
        Integer priorityLevel,
        String status,
        long createdAt,
        long updatedAt,
        long expiryTs
) {

    /**
     * Factory method — converts a domain Reservation into a response DTO.
     */
    public static ReservationResponse fromDomain(Reservation r) {
        return new ReservationResponse(
                r.getReservationId(),
                r.getShipmentId(),
                r.getCapacityType(),
                r.getStockPk(),
                r.getStockSk(),
                r.getQuantity(),
                r.getClassFlag(),
                r.getPriorityLevel(),
                r.getStatus(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getExpiryTs()
        );
    }
}
