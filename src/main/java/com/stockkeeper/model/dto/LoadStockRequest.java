package com.stockkeeper.model.dto;

import com.stockkeeper.validation.ValidTransitionRequest;

/**
 * Request DTO for POST /stock/load.
 *
 * Same identifying fields as CommitStockRequest — derives the same
 * reservation_id to transition COMMITTED → LOADED.
 */
@ValidTransitionRequest
public record LoadStockRequest(

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
