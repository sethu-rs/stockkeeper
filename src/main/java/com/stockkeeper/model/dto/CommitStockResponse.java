package com.stockkeeper.model.dto;

/**
 * Response DTO for POST /stock/commit.
 *
 * @param reservation  full reservation details (status will be COMMITTED)
 * @param idempotent   true if the reservation was already in COMMITTED state
 */
public record CommitStockResponse(
        ReservationResponse reservation,
        boolean idempotent
) {
}
