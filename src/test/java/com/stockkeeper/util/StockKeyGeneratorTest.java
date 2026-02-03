package com.stockkeeper.util;

import com.stockkeeper.model.dto.HoldStockRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for deterministic key generation.
 *
 * These tests verify the exact key patterns specified in the design document.
 * No Spring context needed — StockKeyGenerator is a pure static utility.
 */
class StockKeyGeneratorTest {

    // -----------------------------------------------------------------------
    // FLIGHT key generation
    // -----------------------------------------------------------------------

    @Test
    void shouldGenerateFlightStockPk() {
        HoldStockRequest req = new HoldStockRequest(
                "SHP-001", "FLIGHT", 100, "GENERAL", 1,
                "BA-2173", "2025-08-15", "2025-08-15T14:30:00Z",
                null, null, null, null,
                null, null
        );

        assertEquals("FLIGHT#BA-2173#2025-08-15", StockKeyGenerator.generateStockPk(req));
    }

    @Test
    void shouldGenerateFlightStockSk() {
        HoldStockRequest req = new HoldStockRequest(
                "SHP-001", "FLIGHT", 100, "GENERAL", 1,
                "BA-2173", "2025-08-15", "2025-08-15T14:30:00Z",
                null, null, null, null,
                null, null
        );

        assertEquals("WINDOW#2025-08-15T14:30:00Z", StockKeyGenerator.generateStockSk(req));
    }

    // -----------------------------------------------------------------------
    // WAREHOUSE key generation
    // -----------------------------------------------------------------------

    @Test
    void shouldGenerateWarehouseStockPk() {
        HoldStockRequest req = new HoldStockRequest(
                "SHP-002", "WAREHOUSE", 50, "GENERAL", 1,
                null, null, null,
                "WH-LHR-01", "COLD", "LHR", "JFK",
                null, null
        );

        assertEquals("WH#WH-LHR-01#COLD", StockKeyGenerator.generateStockPk(req));
    }

    @Test
    void shouldGenerateWarehouseStockSk() {
        HoldStockRequest req = new HoldStockRequest(
                "SHP-002", "WAREHOUSE", 50, "GENERAL", 1,
                null, null, null,
                "WH-LHR-01", "COLD", "LHR", "JFK",
                null, null
        );

        assertEquals("SEGMENT#LHR#JFK", StockKeyGenerator.generateStockSk(req));
    }

    // -----------------------------------------------------------------------
    // ULD key generation
    // -----------------------------------------------------------------------

    @Test
    void shouldGenerateUldStockPk() {
        HoldStockRequest req = new HoldStockRequest(
                "SHP-003", "ULD", 200, "GENERAL", 1,
                null, null, null,
                null, null, null, null,
                "ULD-AKE-12345", "LHR"
        );

        assertEquals("ULD#ULD-AKE-12345", StockKeyGenerator.generateStockPk(req));
    }

    @Test
    void shouldGenerateUldStockSk() {
        HoldStockRequest req = new HoldStockRequest(
                "SHP-003", "ULD", 200, "GENERAL", 1,
                null, null, null,
                null, null, null, null,
                "ULD-AKE-12345", "LHR"
        );

        assertEquals("STATE#LHR", StockKeyGenerator.generateStockSk(req));
    }

    // -----------------------------------------------------------------------
    // Reservation ID generation (deterministic — the core of idempotency)
    // -----------------------------------------------------------------------

    @Test
    void shouldGenerateDeterministicReservationId() {
        HoldStockRequest req = new HoldStockRequest(
                "SHP-001", "FLIGHT", 100, "GENERAL", 1,
                "BA-2173", "2025-08-15", "2025-08-15T14:30:00Z",
                null, null, null, null,
                null, null
        );

        String reservationId = StockKeyGenerator.generateReservationId(req);

        assertEquals(
                "RESV#SHP-001#FLIGHT#FLIGHT#BA-2173#2025-08-15#WINDOW#2025-08-15T14:30:00Z",
                reservationId
        );
    }

    @Test
    void shouldProduceSameReservationIdForSameInputs() {
        // Same business fields → same reservation_id (idempotency guarantee)
        HoldStockRequest req1 = new HoldStockRequest(
                "SHP-001", "FLIGHT", 100, "GENERAL", 1,
                "BA-2173", "2025-08-15", "2025-08-15T14:30:00Z",
                null, null, null, null,
                null, null
        );
        HoldStockRequest req2 = new HoldStockRequest(
                "SHP-001", "FLIGHT", 100, "GENERAL", 1,
                "BA-2173", "2025-08-15", "2025-08-15T14:30:00Z",
                null, null, null, null,
                null, null
        );

        assertEquals(
                StockKeyGenerator.generateReservationId(req1),
                StockKeyGenerator.generateReservationId(req2)
        );
    }

    @Test
    void shouldProduceDifferentReservationIdForDifferentShipments() {
        HoldStockRequest req1 = new HoldStockRequest(
                "SHP-001", "FLIGHT", 100, "GENERAL", 1,
                "BA-2173", "2025-08-15", "2025-08-15T14:30:00Z",
                null, null, null, null,
                null, null
        );
        HoldStockRequest req2 = new HoldStockRequest(
                "SHP-999", "FLIGHT", 100, "GENERAL", 1,
                "BA-2173", "2025-08-15", "2025-08-15T14:30:00Z",
                null, null, null, null,
                null, null
        );

        assertNotEquals(
                StockKeyGenerator.generateReservationId(req1),
                StockKeyGenerator.generateReservationId(req2)
        );
    }

    // -----------------------------------------------------------------------
    // Field validation
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnNoErrorsForValidFlightFields() {
        HoldStockRequest req = new HoldStockRequest(
                "SHP-001", "FLIGHT", 100, "GENERAL", 1,
                "BA-2173", "2025-08-15", "2025-08-15T14:30:00Z",
                null, null, null, null,
                null, null
        );

        List<String> errors = StockKeyGenerator.validateKeyFields(req);
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    @Test
    void shouldReturnErrorsForMissingFlightFields() {
        HoldStockRequest req = new HoldStockRequest(
                "SHP-001", "FLIGHT", 100, "GENERAL", 1,
                null, null, null,   // flightId, departureDate, departureDatetime all missing
                null, null, null, null,
                null, null
        );

        List<String> errors = StockKeyGenerator.validateKeyFields(req);
        assertEquals(3, errors.size());
        assertTrue(errors.stream().allMatch(e -> e.contains("FLIGHT")));
    }

    @Test
    void shouldReturnErrorsForMissingWarehouseFields() {
        HoldStockRequest req = new HoldStockRequest(
                "SHP-001", "WAREHOUSE", 50, "GENERAL", 1,
                null, null, null,
                null, null, null, null,  // all warehouse fields missing
                null, null
        );

        List<String> errors = StockKeyGenerator.validateKeyFields(req);
        assertEquals(4, errors.size());
        assertTrue(errors.stream().allMatch(e -> e.contains("WAREHOUSE")));
    }

    @Test
    void shouldReturnErrorsForMissingUldFields() {
        HoldStockRequest req = new HoldStockRequest(
                "SHP-001", "ULD", 200, "GENERAL", 1,
                null, null, null,
                null, null, null, null,
                null, null  // uldId and currentLocation missing
        );

        List<String> errors = StockKeyGenerator.validateKeyFields(req);
        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(e -> e.contains("ULD")));
    }

    @Test
    void shouldReturnErrorForUnknownCapacityType() {
        HoldStockRequest req = new HoldStockRequest(
                "SHP-001", "TRUCK", 100, "GENERAL", 1,
                null, null, null,
                null, null, null, null,
                null, null
        );

        List<String> errors = StockKeyGenerator.validateKeyFields(req);
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("Unknown capacity type"));
    }
}
