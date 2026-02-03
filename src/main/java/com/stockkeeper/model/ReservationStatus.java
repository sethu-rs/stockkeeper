package com.stockkeeper.model;

/**
 * Reservation lifecycle states.
 *
 * The allowed transitions between these states are defined in capacity-config.yml
 * and enforced by the validation layer BEFORE any DynamoDB write.
 *
 * Lifecycle: HELD → COMMITTED → LOADED → RELEASED
 *                 └──────────────────────→ RELEASED  (early release from any non-terminal state)
 */
public enum ReservationStatus {
    HELD,
    COMMITTED,
    LOADED,
    RELEASED;

    /**
     * Maps an operation endpoint name to its target status.
     * e.g. "commit" → COMMITTED, "load" → LOADED, "release" → RELEASED.
     */
    public static ReservationStatus fromOperation(String operation) {
        return switch (operation.toLowerCase()) {
            case "hold" -> HELD;
            case "commit" -> COMMITTED;
            case "load" -> LOADED;
            case "release" -> RELEASED;
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };
    }
}
