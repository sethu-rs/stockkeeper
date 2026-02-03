package com.stockkeeper.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

/**
 * Root configuration object that maps the entire capacity-config.yml file.
 *
 * Structure mirrors the YAML exactly:
 *   capacityTypes:
 *     FLIGHT: { allowedClassFlags: [...], maxHoldDurationMinutes: 60, ... }
 *     WAREHOUSE: { ... }
 *     ULD: { ... }
 *   allowedTransitions:
 *     HELD: [COMMITTED, RELEASED]
 *     ...
 *   terminalStates: [RELEASED]
 */
@Getter
@Setter
@ToString
public class CapacityConfig {

    /**
     * Map of capacity type name → per-type rules.
     * Keys: FLIGHT, WAREHOUSE, ULD (must match the capacity_type attribute in DynamoDB).
     */
    private Map<String, CapacityTypeConfig> capacityTypes;

    /**
     * Allowed state transitions.
     * Key = current state, Value = list of valid target states.
     * Any transition not present here is rejected at the validation layer
     * BEFORE any DynamoDB call is made.
     */
    private Map<String, List<String>> allowedTransitions;

    /**
     * Terminal states — once a reservation reaches one of these,
     * no further transitions are allowed.
     */
    private List<String> terminalStates;
}
