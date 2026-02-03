package com.stockkeeper.util;

import com.stockkeeper.model.dto.StockKeySource;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic key generation utility.
 *
 * This class is the SINGLE place where DynamoDB keys are constructed.
 * It enforces the key patterns defined in the design document:
 *
 *   CapacityStock pk / sk:
 *     FLIGHT:    FLIGHT#<flight_id>#<dep_date>     / WINDOW#<departure_datetime>
 *     WAREHOUSE: WH#<warehouse_id>#<zone_type>     / SEGMENT#<origin>#<destination>
 *     ULD:       ULD#<uld_id>                      / STATE#<current_location>
 *
 *   Reservation id:
 *     RESV#<shipment_id>#<capacity_type>#<stock_pk>#<stock_sk>
 *
 * Design contract: reservation_id is NEVER random and NEVER client-provided.
 * The same set of business fields always produces the same key, which is the
 * foundation of the natural idempotency strategy.
 */
public final class StockKeyGenerator {

    private StockKeyGenerator() {
        // Utility class — no instantiation
    }

    // -----------------------------------------------------------------------
    // Stock key generation
    // -----------------------------------------------------------------------

    /**
     * Builds the CapacityStock partition key from the request's business fields.
     *
     * @throws IllegalArgumentException if capacityType is unknown
     */
    public static String generateStockPk(StockKeySource source) {
        return switch (source.capacityType()) {
            case "FLIGHT" -> "FLIGHT#" + source.flightId() + "#" + source.departureDate();
            case "WAREHOUSE" -> "WH#" + source.warehouseId() + "#" + source.zoneType();
            case "ULD" -> "ULD#" + source.uldId();
            default -> throw new IllegalArgumentException(
                    "Cannot generate stock pk for unknown capacity type: " + source.capacityType());
        };
    }

    /**
     * Builds the CapacityStock sort key from the request's business fields.
     *
     * @throws IllegalArgumentException if capacityType is unknown
     */
    public static String generateStockSk(StockKeySource source) {
        return switch (source.capacityType()) {
            case "FLIGHT" -> "WINDOW#" + source.departureDatetime();
            case "WAREHOUSE" -> "SEGMENT#" + source.origin() + "#" + source.destination();
            case "ULD" -> "STATE#" + source.currentLocation();
            default -> throw new IllegalArgumentException(
                    "Cannot generate stock sk for unknown capacity type: " + source.capacityType());
        };
    }

    // -----------------------------------------------------------------------
    // Reservation ID generation (deterministic — the core of idempotency)
    // -----------------------------------------------------------------------

    /**
     * Derives the deterministic reservation_id from raw components.
     *
     * Format: RESV#<shipment_id>#<capacity_type>#<stock_pk>#<stock_sk>
     */
    public static String generateReservationId(String shipmentId, String capacityType,
                                               String stockPk, String stockSk) {
        return "RESV#" + shipmentId + "#" + capacityType + "#" + stockPk + "#" + stockSk;
    }

    /**
     * Convenience overload that derives pk/sk from the source first,
     * then builds the reservation_id.
     */
    public static String generateReservationId(StockKeySource source) {
        String pk = generateStockPk(source);
        String sk = generateStockSk(source);
        return generateReservationId(source.shipmentId(), source.capacityType(), pk, sk);
    }

    // -----------------------------------------------------------------------
    // Field-presence validation (used by custom Bean Validation validators)
    // -----------------------------------------------------------------------

    /**
     * Returns a list of missing-field error messages for the given source.
     * An empty list means all required fields are present.
     *
     * Each capacity type requires a different set of fields to construct
     * its pk/sk. This method checks that exactly those fields are non-blank.
     */
    public static List<String> validateKeyFields(StockKeySource source) {
        List<String> errors = new ArrayList<>();

        if (isBlank(source.shipmentId())) {
            errors.add("shipmentId is required");
        }
        if (isBlank(source.capacityType())) {
            errors.add("capacityType is required");
            return errors; // Can't validate type-specific fields without knowing the type
        }

        switch (source.capacityType()) {
            case "FLIGHT" -> {
                if (isBlank(source.flightId())) errors.add("flightId is required for FLIGHT capacity");
                if (isBlank(source.departureDate())) errors.add("departureDate is required for FLIGHT capacity");
                if (isBlank(source.departureDatetime())) errors.add("departureDatetime is required for FLIGHT capacity");
            }
            case "WAREHOUSE" -> {
                if (isBlank(source.warehouseId())) errors.add("warehouseId is required for WAREHOUSE capacity");
                if (isBlank(source.zoneType())) errors.add("zoneType is required for WAREHOUSE capacity");
                if (isBlank(source.origin())) errors.add("origin is required for WAREHOUSE capacity");
                if (isBlank(source.destination())) errors.add("destination is required for WAREHOUSE capacity");
            }
            case "ULD" -> {
                if (isBlank(source.uldId())) errors.add("uldId is required for ULD capacity");
                if (isBlank(source.currentLocation())) errors.add("currentLocation is required for ULD capacity");
            }
            default -> errors.add("Unknown capacity type: " + source.capacityType());
        }

        return errors;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
