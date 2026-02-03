package com.stockkeeper.model.dto;

/**
 * Response DTO for POST /stock/release.
 *
 * @param reservation  full reservation details (status will be RELEASED)
 * @param idempotent   true if the reservation was already in RELEASED state
 */
public record ReleaseStockResponse(
        ReservationResponse reservation,
        boolean idempotent
) {
}
