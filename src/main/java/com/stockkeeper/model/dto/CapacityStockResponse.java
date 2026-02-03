package com.stockkeeper.model.dto;

import com.stockkeeper.model.CapacityStock;

import java.util.List;

/**
 * Response DTO for GET /stocks and GET /stocks/{pk}/{sk}.
 *
 * Maps 1:1 from the {@link CapacityStock} domain model.
 * Jackson's SNAKE_CASE naming strategy (configured in application.yml) ensures
 * field names like {@code capacityType} serialize as {@code capacity_type}.
 */
public record CapacityStockResponse(
        String pk,
        String sk,
        String capacityType,
        int totalCapacity,
        int availableCapacity,
        int heldCapacity,
        int committedCapacity,
        int loadedCapacity,
        String unitOfMeasure,
        List<String> classFlags,
        int priorityLevel,
        long expiryTime
) {

    /** Factory method — converts a domain CapacityStock into a response DTO. */
    public static CapacityStockResponse fromDomain(CapacityStock s) {
        return new CapacityStockResponse(
                s.getPk(),
                s.getSk(),
                s.getCapacityType(),
                s.getTotalCapacity(),
                s.getAvailableCapacity(),
                s.getHeldCapacity(),
                s.getCommittedCapacity(),
                s.getLoadedCapacity(),
                s.getUnitOfMeasure(),
                s.getClassFlags(),
                s.getPriorityLevel(),
                s.getExpiryTime()
        );
    }
}
