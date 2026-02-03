package com.stockkeeper.model.dto;

/**
 * Shared interface for any request DTO that carries the business fields
 * needed to derive DynamoDB keys (stock pk/sk and reservation_id).
 *
 * Both HoldStockRequest and the transition requests (commit/load/release)
 * implement this interface so that {@link com.stockkeeper.util.StockKeyGenerator}
 * and the custom validators can work polymorphically across all request types.
 *
 * The accessor method names match Java record component names, so records
 * that declare these components automatically satisfy this interface.
 */
public interface StockKeySource {

    String shipmentId();

    String capacityType();

    // ----- FLIGHT fields -----
    String flightId();
    String departureDate();
    String departureDatetime();

    // ----- WAREHOUSE fields -----
    String warehouseId();
    String zoneType();
    String origin();
    String destination();

    // ----- ULD fields -----
    String uldId();
    String currentLocation();
}
