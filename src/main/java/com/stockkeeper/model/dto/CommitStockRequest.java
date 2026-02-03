package com.stockkeeper.model.dto;

import com.stockkeeper.validation.ValidTransitionRequest;

/**
 * Request DTO for POST /stock/commit.
 *
 * Carries the business fields needed to deterministically derive the
 * reservation_id (via StockKeyGenerator). The service layer uses this to
 * look up the existing reservation and transition it HELD → COMMITTED.
 *
 * No quantity field — the original quantity is read from the reservation.
 * No idempotency key — the deterministic reservation_id IS the idempotency key.
 */
@ValidTransitionRequest
public record CommitStockRequest(

        String shipmentId,
        String capacityType,

        // FLIGHT
        String flightId,
        String departureDate,
        String departureDatetime,

        // WAREHOUSE
        String warehouseId,
        String zoneType,
        String origin,
        String destination,

        // ULD
        String uldId,
        String currentLocation

) implements StockKeySource {
}
