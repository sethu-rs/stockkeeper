package com.stockkeeper.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates DynamoDB tables and seeds sample data on application startup.
 *
 * This is a LOCAL DEVELOPMENT convenience — in production, tables would be
 * created via CloudFormation / CDK / Terraform, not application code.
 *
 * Runs after the application context is fully ready so all beans (including
 * the DynamoDbClient) are available.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamoDbTableInitializer {

    private final DynamoDbClient dynamoDbClient;

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        try {
            boolean stockCreated = createCapacityStockTableIfNotExists();
            createReservationsTableIfNotExists();

            // Only seed data when the table was just created (first run)
            if (stockCreated) {
                seedSampleStockData();
            }

            log.info("DynamoDB table initialization complete");
        } catch (SdkException e) {
            log.warn("Could not initialize DynamoDB tables. Is DynamoDB Local running? Error: {}",
                    e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Table creation
    // -----------------------------------------------------------------------

    /**
     * Creates the CapacityStock table per the design document:
     *   - Partition key: pk (String)
     *   - Sort key: sk (String)
     *   - Billing: PAY_PER_REQUEST
     *
     * @return true if the table was created; false if it already existed.
     */
    private boolean createCapacityStockTableIfNotExists() {
        if (tableExists("CapacityStock")) {
            log.info("Table CapacityStock already exists");
            return false;
        }

        dynamoDbClient.createTable(CreateTableRequest.builder()
                .tableName("CapacityStock")
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()
                )
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()
                )
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());

        log.info("Created table: CapacityStock");
        return true;
    }

    /**
     * Creates the Reservations table per the design document:
     *   - Partition key: reservation_id (String)
     *   - TTL attribute: expiry_ts (configured separately in real AWS)
     *   - Billing: PAY_PER_REQUEST
     */
    private void createReservationsTableIfNotExists() {
        if (tableExists("Reservations")) {
            log.info("Table Reservations already exists");
            return;
        }

        dynamoDbClient.createTable(CreateTableRequest.builder()
                .tableName("Reservations")
                .keySchema(
                        KeySchemaElement.builder().attributeName("reservation_id").keyType(KeyType.HASH).build()
                )
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("reservation_id").attributeType(ScalarAttributeType.S).build()
                )
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());

        log.info("Created table: Reservations (TTL on expiry_ts should be enabled via AWS console/CLI)");
    }

    private boolean tableExists(String tableName) {
        try {
            dynamoDbClient.describeTable(DescribeTableRequest.builder()
                    .tableName(tableName)
                    .build());
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Sample data seeding (makes the app demo-ready out of the box)
    // -----------------------------------------------------------------------

    private void seedSampleStockData() {
        long futureExpiry = Instant.now().plusSeconds(86400).getEpochSecond();

        // Sample FLIGHT stock
        putStockItem(
                "FLIGHT#BA-2173#2025-08-15", "WINDOW#2025-08-15T14:30:00Z",
                "FLIGHT", 10000, 10000, 0, 0, 0,
                "KG", List.of("GENERAL", "PRIORITY", "EXPRESS"), 1, futureExpiry
        );

        // Sample WAREHOUSE stock
        putStockItem(
                "WH#WH-LHR-01#COLD", "SEGMENT#LHR#JFK",
                "WAREHOUSE", 500, 500, 0, 0, 0,
                "CBM", List.of("GENERAL", "COLD_STORAGE"), 2, futureExpiry
        );

        // Sample ULD stock
        putStockItem(
                "ULD#ULD-AKE-12345", "STATE#LHR",
                "ULD", 1500, 1500, 0, 0, 0,
                "KG", List.of("GENERAL", "PRIORITY"), 1, futureExpiry
        );

        log.info("Seeded 3 sample CapacityStock items (FLIGHT, WAREHOUSE, ULD)");
    }

    private void putStockItem(String pk, String sk, String capacityType,
                              int total, int available, int held, int committed, int loaded,
                              String uom, List<String> classFlags, int priority, long expiry) {

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(pk));
        item.put("sk", AttributeValue.fromS(sk));
        item.put("capacity_type", AttributeValue.fromS(capacityType));
        item.put("total_capacity", AttributeValue.fromN(String.valueOf(total)));
        item.put("available_capacity", AttributeValue.fromN(String.valueOf(available)));
        item.put("held_capacity", AttributeValue.fromN(String.valueOf(held)));
        item.put("committed_capacity", AttributeValue.fromN(String.valueOf(committed)));
        item.put("loaded_capacity", AttributeValue.fromN(String.valueOf(loaded)));
        item.put("unit_of_measure", AttributeValue.fromS(uom));
        item.put("class_flags", AttributeValue.fromL(
                classFlags.stream().map(AttributeValue::fromS).toList()
        ));
        item.put("priority_level", AttributeValue.fromN(String.valueOf(priority)));
        item.put("expiry_time", AttributeValue.fromN(String.valueOf(expiry)));

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName("CapacityStock")
                .item(item)
                .build());
    }
}
