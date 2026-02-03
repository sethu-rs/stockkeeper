package com.stockkeeper.model.dto;

import com.stockkeeper.validation.ValidTransitionRequest;

/**
 * Request DTO for POST /stock/release.
 *
 * Same identifying fields as CommitStockRequest — derives the same
 * reservation_id to transition from the current state → RELEASED.
 *
 * Release is valid from HELD, COMMITTED, or LOADED.
 * The capacity counter that gets credited back depends on the current state
 * (read from the existing reservation at execution time).
 */
@ValidTransitionRequest
public record ReleaseStockRequest(

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
