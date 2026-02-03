package com.stockkeeper.model.dto;

/**
 * Response DTO for POST /stock/load.
 *
 * @param reservation  full reservation details (status will be LOADED)
 * @param idempotent   true if the reservation was already in LOADED state
 */
public record LoadStockResponse(
        ReservationResponse reservation,
        boolean idempotent
) {
}
