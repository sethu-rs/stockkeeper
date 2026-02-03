package com.stockkeeper.model.dto;

import com.stockkeeper.validation.ValidHoldStockRequest;

/**
 * Request DTO for POST /stock/hold.
 *
 * Contains all fields needed to:
 *   1. Validate the request against capacity-config.yml (before DynamoDB)
 *   2. Derive the CapacityStock pk/sk (key pattern depends on capacityType)
 *   3. Derive the deterministic reservation_id
 *   4. Execute the hold transaction
 *
 * The {@link ValidHoldStockRequest} annotation triggers class-level validation
 * that checks capacity type support, class flag validity, quantity > 0, and
 * presence of type-specific key fields — all using the in-memory config.
 *
 * Only the fields relevant to the requested capacityType need to be populated.
 * For example, a FLIGHT hold only needs flightId, departureDate, departureDatetime.
 */
@ValidHoldStockRequest
public record HoldStockRequest(

        // ----- Common fields -----
        String shipmentId,
        String capacityType,
        int requestedQuantity,
        String classFlag,
        Integer priorityLevel,

        // ----- FLIGHT-specific fields -----
        String flightId,
        String departureDate,
        String departureDatetime,

        // ----- WAREHOUSE-specific fields -----
        String warehouseId,
        String zoneType,
        String origin,
        String destination,

        // ----- ULD-specific fields -----
        String uldId,
        String currentLocation

) implements StockKeySource {
}
