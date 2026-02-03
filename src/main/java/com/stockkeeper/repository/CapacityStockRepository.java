package com.stockkeeper.repository;

import com.stockkeeper.model.CapacityStock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repository for read operations on the CapacityStock DynamoDB table.
 *
 * All write operations on this table happen through TransactWriteItems
 * in the service layer (because writes are always part of a multi-table
 * transaction with the Reservations table).
 *
 * Uses the AWS SDK v2 low-level client directly — no Spring Data DynamoDB.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CapacityStockRepository {

    public static final String TABLE_NAME = "CapacityStock";

    private final DynamoDbClient dynamoDbClient;

    // -----------------------------------------------------------------------
    // Single-item lookup (GetItem)
    // -----------------------------------------------------------------------

    /**
     * Fetches a single CapacityStock item by its composite key (pk + sk).
     *
     * Uses DynamoDB GetItem — strongly consistent read to ensure the service
     * layer sees the latest capacity values before making decisions.
     */
    public Optional<CapacityStock> findByPkAndSk(String pk, String sk) {
        GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "pk", AttributeValue.fromS(pk),
                        "sk", AttributeValue.fromS(sk)
                ))
                .consistentRead(true)
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(fromAttributeMap(response.item()));
    }

    // -----------------------------------------------------------------------
    // Query by partition key (DynamoDB Query — efficient, uses key index)
    // -----------------------------------------------------------------------

    /**
     * Returns all CapacityStock items that share the given partition key.
     *
     * For example, pk="FLIGHT#BA-2173#2025-08-15" returns all departure
     * windows for that flight on that date.
     */
    public List<CapacityStock> queryByPk(String pk) {
        QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(TABLE_NAME)
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(pk)
                ))
                .build());

        return response.items().stream()
                .map(this::fromAttributeMap)
                .toList();
    }

    // -----------------------------------------------------------------------
    // Scan operations (full table scan — acceptable for local demo)
    // -----------------------------------------------------------------------

    /** Returns all CapacityStock items in the table. */
    public List<CapacityStock> findAll() {
        ScanResponse response = dynamoDbClient.scan(ScanRequest.builder()
                .tableName(TABLE_NAME)
                .build());

        return response.items().stream()
                .map(this::fromAttributeMap)
                .toList();
    }

    /** Returns all CapacityStock items matching the given capacity_type. */
    public List<CapacityStock> findByCapacityType(String capacityType) {
        ScanResponse response = dynamoDbClient.scan(ScanRequest.builder()
                .tableName(TABLE_NAME)
                .filterExpression("capacity_type = :ct")
                .expressionAttributeValues(Map.of(
                        ":ct", AttributeValue.fromS(capacityType)
                ))
                .build());

        return response.items().stream()
                .map(this::fromAttributeMap)
                .toList();
    }

    // -----------------------------------------------------------------------
    // DynamoDB AttributeValue → Domain model mapping
    // -----------------------------------------------------------------------

    /**
     * Converts a raw DynamoDB item map into a CapacityStock domain object.
     *
     * DynamoDB uses snake_case attribute names; Java uses camelCase.
     * This method bridges the two naming conventions.
     */
    private CapacityStock fromAttributeMap(Map<String, AttributeValue> item) {
        return CapacityStock.builder()
                .pk(getString(item, "pk"))
                .sk(getString(item, "sk"))
                .capacityType(getString(item, "capacity_type"))
                .totalCapacity(getInt(item, "total_capacity"))
                .availableCapacity(getInt(item, "available_capacity"))
                .heldCapacity(getInt(item, "held_capacity"))
                .committedCapacity(getInt(item, "committed_capacity"))
                .loadedCapacity(getInt(item, "loaded_capacity"))
                .unitOfMeasure(getString(item, "unit_of_measure"))
                .classFlags(getStringList(item, "class_flags"))
                .priorityLevel(getInt(item, "priority_level"))
                .expiryTime(getLong(item, "expiry_time"))
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

    private List<String> getStringList(Map<String, AttributeValue> item, String key) {
        AttributeValue val = item.get(key);
        if (val == null || !val.hasL()) {
            return Collections.emptyList();
        }
        return val.l().stream()
                .map(AttributeValue::s)
                .toList();
    }
}
