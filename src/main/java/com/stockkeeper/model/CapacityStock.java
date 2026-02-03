package com.stockkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Domain model for the CapacityStock DynamoDB table — the unified capacity ledger.
 *
 * Each row represents a single bookable capacity slot (e.g. a flight window,
 * a warehouse segment, or a ULD state).
 *
 * Key patterns (pk / sk):
 *   FLIGHT:    FLIGHT#<flight_id>#<dep_date>     / WINDOW#<departure_datetime>
 *   WAREHOUSE: WH#<warehouse_id>#<zone_type>     / SEGMENT#<origin>#<destination>
 *   ULD:       ULD#<uld_id>                      / STATE#<current_location>
 *
 * Field names use camelCase in Java; the repository layer maps to/from
 * DynamoDB's snake_case attribute names.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapacityStock {

    /** Partition key — identifies the capacity resource. */
    private String pk;

    /** Sort key — identifies the specific slot within the resource. */
    private String sk;

    /** Discriminator: FLIGHT | WAREHOUSE | ULD. */
    private String capacityType;

    /** Total capacity provisioned for this slot. */
    private int totalCapacity;

    /** Capacity currently available for new holds. */
    private int availableCapacity;

    /** Capacity currently held (reserved but not yet committed). */
    private int heldCapacity;

    /** Capacity that has been committed (confirmed reservations). */
    private int committedCapacity;

    /** Capacity that has been physically loaded. */
    private int loadedCapacity;

    /** Unit of measure — e.g. KG, CBM. Driven by capacity-config.yml. */
    private String unitOfMeasure;

    /** Class flags supported by this capacity slot (e.g. GENERAL, PRIORITY). */
    private List<String> classFlags;

    /** Priority level for scheduling / allocation. */
    private int priorityLevel;

    /** Epoch-second expiry time for time-bounded capacity windows. */
    private long expiryTime;
}
