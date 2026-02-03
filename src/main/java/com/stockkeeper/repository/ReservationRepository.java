package com.stockkeeper.repository;

import com.stockkeeper.model.Reservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Repository for the Reservations DynamoDB table.
 *
 * Provides:
 *   - Read operations (GetItem by reservation_id)
 *   - Attribute mapping between DynamoDB and domain model
 *   - toAttributeMap() for building Put items in transactions
 *
 * All writes happen through TransactWriteItems in the service layer
 * because every mutation is part of an atomic transaction with CapacityStock.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ReservationRepository {

    public static final String TABLE_NAME = "Reservations";

    private final DynamoDbClient dynamoDbClient;

    // -----------------------------------------------------------------------
    // Read operations
    // -----------------------------------------------------------------------

    /**
     * Fetches a reservation by its deterministic ID.
     *
     * Uses consistent read to ensure we see the latest state — critical
     * for the idempotency check in transition operations (commit/load/release).
     * Without consistent read, we might miss a concurrent state change and
     * attempt a transition that has already been applied.
     */
    public Optional<Reservation> findById(String reservationId) {
        GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "reservation_id", AttributeValue.fromS(reservationId)
                ))
                .consistentRead(true)
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(fromAttributeMap(response.item()));
    }

    // -----------------------------------------------------------------------
    // Domain → DynamoDB mapping (used by service layer to build Put items)
    // -----------------------------------------------------------------------

    /**
     * Converts a Reservation domain object into a DynamoDB attribute map.
     *
     * This map is used as the Item in the Put operation within
     * TransactWriteItems during the HOLD flow. Nullable fields
     * (classFlag, priorityLevel) are only included when non-null
     * to keep the DynamoDB item clean.
     */
    public Map<String, AttributeValue> toAttributeMap(Reservation r) {
        Map<String, AttributeValue> item = new HashMap<>();

        item.put("reservation_id", AttributeValue.fromS(r.getReservationId()));
        item.put("shipment_id", AttributeValue.fromS(r.getShipmentId()));
        item.put("capacity_type", AttributeValue.fromS(r.getCapacityType()));
        item.put("stock_pk", AttributeValue.fromS(r.getStockPk()));
        item.put("stock_sk", AttributeValue.fromS(r.getStockSk()));
        item.put("quantity", AttributeValue.fromN(String.valueOf(r.getQuantity())));
        item.put("status", AttributeValue.fromS(r.getStatus()));
        item.put("created_at", AttributeValue.fromN(String.valueOf(r.getCreatedAt())));
        item.put("updated_at", AttributeValue.fromN(String.valueOf(r.getUpdatedAt())));
        item.put("expiry_ts", AttributeValue.fromN(String.valueOf(r.getExpiryTs())));

        // Nullable fields — only write to DynamoDB if present
        if (r.getClassFlag() != null) {
            item.put("class_flag", AttributeValue.fromS(r.getClassFlag()));
        }
        if (r.getPriorityLevel() != null) {
            item.put("priority_level", AttributeValue.fromN(String.valueOf(r.getPriorityLevel())));
        }

        return item;
    }

    // -----------------------------------------------------------------------
    // DynamoDB → Domain mapping
    // -----------------------------------------------------------------------

    private Reservation fromAttributeMap(Map<String, AttributeValue> item) {
        return Reservation.builder()
                .reservationId(getString(item, "reservation_id"))
                .shipmentId(getString(item, "shipment_id"))
                .capacityType(getString(item, "capacity_type"))
                .stockPk(getString(item, "stock_pk"))
                .stockSk(getString(item, "stock_sk"))
                .quantity(getInt(item, "quantity"))
                .classFlag(getString(item, "class_flag"))
                .priorityLevel(getOptionalInt(item, "priority_level"))
                .status(getString(item, "status"))
                .createdAt(getLong(item, "created_at"))
                .updatedAt(getLong(item, "updated_at"))
                .expiryTs(getLong(item, "expiry_ts"))
                .build();
    }

    // -----------------------------------------------------------------------
    // Safe attribute extraction helpers
    // -----------------------------------------------------------------------

    private String getString(Map<String, AttributeValue> item, String key) {
        AttributeValue val = item.get(key);
        return (val != null && val.s() != null) ? val.s() : null;
    }

    private int getInt(Map<String, AttributeValue> item, String key) {
        AttributeValue val = item.get(key);
        return (val != null && val.n() != null) ? Integer.parseInt(val.n()) : 0;
    }

    private long getLong(Map<String, AttributeValue> item, String key) {
        AttributeValue val = item.get(key);
        return (val != null && val.n() != null) ? Long.parseLong(val.n()) : 0L;
    }

    private Integer getOptionalInt(Map<String, AttributeValue> item, String key) {
        AttributeValue val = item.get(key);
        return (val != null && val.n() != null) ? Integer.parseInt(val.n()) : null;
    }
}
