package com.stockkeeper.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

/**
 * Configuration for a single capacity type (FLIGHT, WAREHOUSE, ULD).
 *
 * Populated from the capacityTypes map in capacity-config.yml.
 * Each entry describes what class flags are valid for that type,
 * how long a HOLD can last, and what unit the capacity is measured in.
 */
@Getter
@Setter
@ToString
public class CapacityTypeConfig {

    /** Class flags allowed for this capacity type (e.g. GENERAL, PRIORITY). */
    private List<String> allowedClassFlags;

    /** Maximum minutes a HOLD reservation stays valid before expiry. */
    private int maxHoldDurationMinutes;

    /** Unit of measure for quantities (e.g. KG, CBM). */
    private String unitOfMeasure;
}
