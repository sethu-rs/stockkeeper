package com.stockkeeper.model.dto;

/**
 * Response DTO for POST /stock/hold.
 *
 * @param reservation  full reservation details
 * @param idempotent   true if this hold already existed (replay of same request);
 *                     false if a new reservation was created. Useful for logging
 *                     and debugging — does NOT change HTTP status (always 200).
 */
public record HoldStockResponse(
        ReservationResponse reservation,
        boolean idempotent
) {
}
