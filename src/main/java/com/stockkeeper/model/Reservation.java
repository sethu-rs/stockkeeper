package com.stockkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Domain model for the Reservations DynamoDB table.
 *
 * Each reservation is uniquely identified by a deterministic composite key:
 *   reservation_id = RESV#<shipment_id>#<capacity_type>#<stock_pk>#<stock_sk>
 *
 * This key is NEVER random and NEVER client-provided — it is always derived
 * from the business fields in the request. This is the foundation of the
 * natural idempotency strategy: the same logical operation always produces
 * the same reservation_id, and DynamoDB conditional writes prevent duplicates.
 *
 * The stock_pk and stock_sk fields reference the CapacityStock row that this
 * reservation draws capacity from. On state transitions (commit, load, release),
 * the service reads these fields to know which stock row to update.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Reservation {

    /**
     * Deterministic primary key.
     * Format: RESV#<shipment_id>#<capacity_type>#<stock_pk>#<stock_sk>
     */
    private String reservationId;

    /** The shipment this reservation belongs to. */
    private String shipmentId;

    /** Capacity type: FLIGHT | WAREHOUSE | ULD. */
    private String capacityType;

    /** CapacityStock partition key this reservation was booked against. */
    private String stockPk;

    /** CapacityStock sort key this reservation was booked against. */
    private String stockSk;

    /** Quantity of capacity reserved, in the unit defined by capacity type. */
    private int quantity;

    /** Class flag assigned to this reservation (e.g. GENERAL, PRIORITY). */
    private String classFlag;

    /** Priority level for this reservation. */
    private Integer priorityLevel;

    /** Current lifecycle status: HELD, COMMITTED, LOADED, or RELEASED. */
    private String status;

    /** Epoch-second timestamp when the reservation was first created. */
    private long createdAt;

    /** Epoch-second timestamp of the most recent status change. */
    private long updatedAt;

    /** TTL attribute — epoch-second expiry used by DynamoDB's TTL feature. */
    private long expiryTs;
}
