package com.stockkeeper.service;

import com.stockkeeper.config.CapacityConfigLoader;
import com.stockkeeper.exception.InsufficientCapacityException;
import com.stockkeeper.exception.InvalidTransitionException;
import com.stockkeeper.exception.ReservationNotFoundException;
import com.stockkeeper.exception.StockNotFoundException;
import com.stockkeeper.model.CapacityStock;
import com.stockkeeper.model.Reservation;
import com.stockkeeper.model.ReservationStatus;
import com.stockkeeper.model.dto.CapacityStockResponse;
import com.stockkeeper.model.dto.CommitStockRequest;
import com.stockkeeper.model.dto.CommitStockResponse;
import com.stockkeeper.model.dto.HoldStockRequest;
import com.stockkeeper.model.dto.HoldStockResponse;
import com.stockkeeper.model.dto.LoadStockRequest;
import com.stockkeeper.model.dto.LoadStockResponse;
import com.stockkeeper.model.dto.ReleaseStockRequest;
import com.stockkeeper.model.dto.ReleaseStockResponse;
import com.stockkeeper.model.dto.ReservationResponse;
import com.stockkeeper.model.dto.StockKeySource;
import com.stockkeeper.repository.CapacityStockRepository;
import com.stockkeeper.repository.ReservationRepository;
import com.stockkeeper.util.StockKeyGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Core service implementing the four stock operations: HOLD, COMMIT, LOAD, RELEASE.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * IDEMPOTENCY STRATEGY (design.md §5)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Idempotency is a business property, NOT a client responsibility.
 *
 *   1. The reservation_id is deterministic:
 *      RESV#<shipment_id>#<capacity_type>#<stock_pk>#<stock_sk>
 *      The same logical operation ALWAYS produces the same key.
 *
 *   2. HOLD uses TransactWriteItems with:
 *      - Put Reservation with attribute_not_exists(reservation_id)
 *      - Update CapacityStock with available_capacity >= :qty
 *      If the reservation already exists, the Put condition fails, we treat
 *      it as idempotent success and return the existing reservation.
 *
 *   3. COMMIT/LOAD/RELEASE read the reservation first. If it is already in
 *      the target (or terminal) state, we return success immediately without
 *      touching DynamoDB again. Otherwise we transact with a condition on
 *      reservation status to prevent concurrent double-transitions.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private static final String CAPACITY_STOCK_TABLE = "CapacityStock";
    private static final String RESERVATIONS_TABLE = "Reservations";

    private final DynamoDbClient dynamoDbClient;
    private final CapacityStockRepository stockRepository;
    private final ReservationRepository reservationRepository;
    private final CapacityConfigLoader configLoader;

    // ═══════════════════════════════════════════════════════════════════════
    // HOLD (design.md §7)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Holds capacity for a shipment.
     *
     * Flow:
     *   1. Derive deterministic keys (stock pk/sk, reservation_id)
     *   2. Verify the stock item exists (fail-fast with 404)
     *   3. Execute TransactWriteItems:
     *        - Update CapacityStock: available -= qty, held += qty
     *          Condition: available_capacity >= :qty
     *        - Put Reservation: status = HELD
     *          Condition: attribute_not_exists(reservation_id)
     *   4. On success → return new reservation
     *   5. On TransactionCanceledException:
     *        - If Put condition failed → reservation already exists → idempotent success
     *        - If Update condition failed → insufficient capacity → 409
     */
    public HoldStockResponse holdStock(HoldStockRequest request) {
        // Step 1: Derive deterministic keys from business fields
        String stockPk = StockKeyGenerator.generateStockPk(request);
        String stockSk = StockKeyGenerator.generateStockSk(request);
        String reservationId = StockKeyGenerator.generateReservationId(
                request.shipmentId(), request.capacityType(), stockPk, stockSk);

        log.info("HOLD: shipmentId={}, reservationId={}, stockPk={}, stockSk={}, qty={}",
                request.shipmentId(), reservationId, stockPk, stockSk, request.requestedQuantity());

        // Step 2: Verify stock exists (gives a clear 404 instead of a confusing
        // ConditionalCheckFailed error if the stock item is missing)
        stockRepository.findByPkAndSk(stockPk, stockSk)
                .orElseThrow(() -> new StockNotFoundException(stockPk, stockSk));

        // Step 3: Build the Reservation object
        long now = Instant.now().getEpochSecond();
        int holdMinutes = configLoader.getMaxHoldDurationMinutes(request.capacityType());
        long expiryTs = now + (holdMinutes * 60L);

        Reservation reservation = Reservation.builder()
                .reservationId(reservationId)
                .shipmentId(request.shipmentId())
                .capacityType(request.capacityType())
                .stockPk(stockPk)
                .stockSk(stockSk)
                .quantity(request.requestedQuantity())
                .classFlag(request.classFlag())
                .priorityLevel(request.priorityLevel())
                .status(ReservationStatus.HELD.name())
                .createdAt(now)
                .updatedAt(now)
                .expiryTs(expiryTs)
                .build();

        // Step 4: Execute atomic transaction
        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(

                            // Item [0]: Update CapacityStock — decrement available, increment held
                            TransactWriteItem.builder()
                                    .update(Update.builder()
                                            .tableName(CAPACITY_STOCK_TABLE)
                                            .key(Map.of(
                                                    "pk", AttributeValue.fromS(stockPk),
                                                    "sk", AttributeValue.fromS(stockSk)
                                            ))
                                            .conditionExpression("available_capacity >= :qty")
                                            .updateExpression(
                                                    "SET available_capacity = available_capacity - :qty, " +
                                                    "held_capacity = held_capacity + :qty")
                                            .expressionAttributeValues(Map.of(
                                                    ":qty", AttributeValue.fromN(
                                                            String.valueOf(request.requestedQuantity()))
                                            ))
                                            .build())
                                    .build(),

                            // Item [1]: Put Reservation — idempotency guard via attribute_not_exists
                            TransactWriteItem.builder()
                                    .put(Put.builder()
                                            .tableName(RESERVATIONS_TABLE)
                                            .item(reservationRepository.toAttributeMap(reservation))
                                            .conditionExpression("attribute_not_exists(reservation_id)")
                                            .build())
                                    .build()
                    )
                    .build());

            log.info("HOLD succeeded: reservationId={}", reservationId);
            return new HoldStockResponse(ReservationResponse.fromDomain(reservation), false);

        } catch (TransactionCanceledException e) {
            return handleHoldConflict(e, reservationId, stockPk, stockSk, request.requestedQuantity());
        }
    }

    /**
     * Interprets a HOLD transaction failure.
     *
     * The transaction has two items:
     *   [0] = CapacityStock Update
     *   [1] = Reservations Put
     *
     * Cancellation reason analysis:
     *   - If [1] is ConditionalCheckFailed → reservation_id already exists.
     *     This means the exact same HOLD was already executed. We fetch and
     *     return the existing reservation. This IS the natural idempotency.
     *
     *   - If [0] is ConditionalCheckFailed → available_capacity < requested qty.
     *     Throw 409 Conflict.
     */
    private HoldStockResponse handleHoldConflict(TransactionCanceledException e,
                                                  String reservationId, String stockPk,
                                                  String stockSk, int qty) {
        List<CancellationReason> reasons = e.cancellationReasons();

        // Check Put condition first — idempotent case takes priority
        // (even if both conditions failed, the reservation existing means
        // the hold was already applied, so capacity was already moved)
        if (reasons.size() > 1 && isConditionalCheckFailed(reasons.get(1))) {
            log.info("HOLD idempotent: reservationId={} already exists, returning existing", reservationId);

            Reservation existing = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Transaction says reservation exists but GetItem returned nothing: "
                            + reservationId));

            return new HoldStockResponse(ReservationResponse.fromDomain(existing), true);
        }

        // Check Update condition — insufficient capacity
        if (!reasons.isEmpty() && isConditionalCheckFailed(reasons.get(0))) {
            throw new InsufficientCapacityException(
                    "Insufficient available capacity: stock pk=" + stockPk
                    + ", sk=" + stockSk + ", requested=" + qty);
        }

        // Unexpected failure
        throw new RuntimeException("HOLD transaction failed: " + e.getMessage(), e);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMMIT (design.md §8)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Commits a held reservation: HELD → COMMITTED.
     *
     * Capacity movement: held_capacity -= qty, committed_capacity += qty
     *
     * Idempotency: if the reservation is already COMMITTED (or in a terminal
     * state like RELEASED), returns success without modifying anything.
     */
    public CommitStockResponse commitStock(CommitStockRequest request) {
        String targetStatus = ReservationStatus.COMMITTED.name();
        Reservation reservation = readAndValidateTransition(request, targetStatus);

        // Idempotent check passed in readAndValidateTransition — if reservation
        // is already in target or terminal state, it returns that reservation.
        if (reservation.getStatus().equals(targetStatus)
                || configLoader.isTerminalState(reservation.getStatus())) {
            log.info("COMMIT idempotent: reservation {} is already {}",
                    reservation.getReservationId(), reservation.getStatus());
            return new CommitStockResponse(ReservationResponse.fromDomain(reservation), true);
        }

        long now = Instant.now().getEpochSecond();
        try {
            executeTransitionTransaction(
                    reservation.getStockPk(), reservation.getStockSk(),
                    "held_capacity", "committed_capacity",
                    reservation.getQuantity(), reservation.getReservationId(),
                    reservation.getStatus(), targetStatus, now);
        } catch (TransactionCanceledException e) {
            Reservation current = handleTransitionConflict(
                    e, reservation.getReservationId(), targetStatus);
            return new CommitStockResponse(ReservationResponse.fromDomain(current), true);
        }

        reservation.setStatus(targetStatus);
        reservation.setUpdatedAt(now);
        log.info("COMMIT succeeded: reservationId={}", reservation.getReservationId());
        return new CommitStockResponse(ReservationResponse.fromDomain(reservation), false);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LOAD (design.md §8)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Loads a committed reservation: COMMITTED → LOADED.
     *
     * Capacity movement: committed_capacity -= qty, loaded_capacity += qty
     */
    public LoadStockResponse loadStock(LoadStockRequest request) {
        String targetStatus = ReservationStatus.LOADED.name();
        Reservation reservation = readAndValidateTransition(request, targetStatus);

        if (reservation.getStatus().equals(targetStatus)
                || configLoader.isTerminalState(reservation.getStatus())) {
            log.info("LOAD idempotent: reservation {} is already {}",
                    reservation.getReservationId(), reservation.getStatus());
            return new LoadStockResponse(ReservationResponse.fromDomain(reservation), true);
        }

        long now = Instant.now().getEpochSecond();
        try {
            executeTransitionTransaction(
                    reservation.getStockPk(), reservation.getStockSk(),
                    "committed_capacity", "loaded_capacity",
                    reservation.getQuantity(), reservation.getReservationId(),
                    reservation.getStatus(), targetStatus, now);
        } catch (TransactionCanceledException e) {
            Reservation current = handleTransitionConflict(
                    e, reservation.getReservationId(), targetStatus);
            return new LoadStockResponse(ReservationResponse.fromDomain(current), true);
        }

        reservation.setStatus(targetStatus);
        reservation.setUpdatedAt(now);
        log.info("LOAD succeeded: reservationId={}", reservation.getReservationId());
        return new LoadStockResponse(ReservationResponse.fromDomain(reservation), false);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RELEASE (design.md §8)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Releases a reservation from any non-terminal state: HELD/COMMITTED/LOADED → RELEASED.
     *
     * Capacity movement depends on the current state:
     *   HELD      → held_capacity -= qty,      available_capacity += qty
     *   COMMITTED → committed_capacity -= qty,  available_capacity += qty
     *   LOADED    → loaded_capacity -= qty,     available_capacity += qty
     *
     * The capacity always returns to available_capacity regardless of which
     * bucket it is currently in.
     */
    public ReleaseStockResponse releaseStock(ReleaseStockRequest request) {
        String targetStatus = ReservationStatus.RELEASED.name();
        Reservation reservation = readAndValidateTransition(request, targetStatus);

        // Already released → idempotent success
        if (configLoader.isTerminalState(reservation.getStatus())) {
            log.info("RELEASE idempotent: reservation {} is already {}",
                    reservation.getReservationId(), reservation.getStatus());
            return new ReleaseStockResponse(ReservationResponse.fromDomain(reservation), true);
        }

        // Determine which capacity bucket to debit based on current state
        String fromBucket = switch (reservation.getStatus()) {
            case "HELD" -> "held_capacity";
            case "COMMITTED" -> "committed_capacity";
            case "LOADED" -> "loaded_capacity";
            default -> throw new InvalidTransitionException(
                    "Cannot release from state: " + reservation.getStatus());
        };

        long now = Instant.now().getEpochSecond();
        try {
            executeTransitionTransaction(
                    reservation.getStockPk(), reservation.getStockSk(),
                    fromBucket, "available_capacity",
                    reservation.getQuantity(), reservation.getReservationId(),
                    reservation.getStatus(), targetStatus, now);
        } catch (TransactionCanceledException e) {
            Reservation current = handleTransitionConflict(
                    e, reservation.getReservationId(), targetStatus);
            return new ReleaseStockResponse(ReservationResponse.fromDomain(current), true);
        }

        reservation.setStatus(targetStatus);
        reservation.setUpdatedAt(now);
        log.info("RELEASE succeeded: reservationId={}", reservation.getReservationId());
        return new ReleaseStockResponse(ReservationResponse.fromDomain(reservation), false);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY operations (GET /stocks)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Lists stock items with optional filtering.
     *
     * Filter priority:
     *   1. pk provided → DynamoDB Query (efficient, uses partition key index)
     *   2. capacityType provided → Scan with filter (acceptable for local demo)
     *   3. No filters → full Scan
     */
    public List<CapacityStockResponse> listStocks(String capacityType, String pk) {
        List<CapacityStock> stocks;

        if (pk != null && !pk.isBlank()) {
            stocks = stockRepository.queryByPk(pk);
        } else if (capacityType != null && !capacityType.isBlank()) {
            stocks = stockRepository.findByCapacityType(capacityType);
        } else {
            stocks = stockRepository.findAll();
        }

        return stocks.stream()
                .map(CapacityStockResponse::fromDomain)
                .toList();
    }

    /** Returns a single stock item, or throws 404. */
    public CapacityStockResponse getStock(String pk, String sk) {
        return stockRepository.findByPkAndSk(pk, sk)
                .map(CapacityStockResponse::fromDomain)
                .orElseThrow(() -> new StockNotFoundException(pk, sk));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Shared transition logic
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Reads the reservation for a transition request and validates that the
     * transition is allowed by the config. Returns the reservation for further
     * processing by the caller.
     *
     * Common to COMMIT, LOAD, and RELEASE.
     */
    private Reservation readAndValidateTransition(StockKeySource request, String targetStatus) {
        String stockPk = StockKeyGenerator.generateStockPk(request);
        String stockSk = StockKeyGenerator.generateStockSk(request);
        String reservationId = StockKeyGenerator.generateReservationId(
                request.shipmentId(), request.capacityType(), stockPk, stockSk);

        log.info("Transition to {}: reservationId={}", targetStatus, reservationId);

        // Read the current reservation state
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        String currentStatus = reservation.getStatus();

        // If already in target or terminal state → caller handles as idempotent
        if (currentStatus.equals(targetStatus) || configLoader.isTerminalState(currentStatus)) {
            return reservation;
        }

        // Validate that this transition is allowed by capacity-config.yml
        if (!configLoader.isTransitionAllowed(currentStatus, targetStatus)) {
            throw new InvalidTransitionException(
                    "Transition " + currentStatus + " → " + targetStatus + " is not allowed. "
                    + "Allowed transitions from " + currentStatus + ": "
                    + configLoader.getCapacityConfig().getAllowedTransitions().get(currentStatus));
        }

        return reservation;
    }

    /**
     * Executes a two-item DynamoDB transaction for state transitions.
     *
     * Item [0]: Update CapacityStock — move quantity between buckets
     *   ConditionExpression: <fromBucket> >= :qty
     *   UpdateExpression: SET <fromBucket> -= :qty, <toBucket> += :qty
     *
     * Item [1]: Update Reservation status
     *   ConditionExpression: status = :expectedStatus
     *   UpdateExpression: SET status = :newStatus, updated_at = :now
     *
     * Note: "status" is a DynamoDB reserved word, so we use ExpressionAttributeNames
     * (#st → status) to avoid parsing errors.
     */
    private void executeTransitionTransaction(String stockPk, String stockSk,
                                              String fromBucket, String toBucket,
                                              int qty, String reservationId,
                                              String expectedStatus, String newStatus,
                                              long now) {
        dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                .transactItems(

                        // Item [0]: Move capacity between buckets
                        TransactWriteItem.builder()
                                .update(Update.builder()
                                        .tableName(CAPACITY_STOCK_TABLE)
                                        .key(Map.of(
                                                "pk", AttributeValue.fromS(stockPk),
                                                "sk", AttributeValue.fromS(stockSk)
                                        ))
                                        .conditionExpression(fromBucket + " >= :qty")
                                        .updateExpression(
                                                "SET " + fromBucket + " = " + fromBucket + " - :qty, "
                                                + toBucket + " = " + toBucket + " + :qty")
                                        .expressionAttributeValues(Map.of(
                                                ":qty", AttributeValue.fromN(String.valueOf(qty))
                                        ))
                                        .build())
                                .build(),

                        // Item [1]: Update reservation status with optimistic lock
                        TransactWriteItem.builder()
                                .update(Update.builder()
                                        .tableName(RESERVATIONS_TABLE)
                                        .key(Map.of(
                                                "reservation_id", AttributeValue.fromS(reservationId)
                                        ))
                                        // "status" is a DynamoDB reserved word → use #st alias
                                        .conditionExpression("#st = :expected")
                                        .updateExpression("SET #st = :newStatus, updated_at = :now")
                                        .expressionAttributeNames(Map.of(
                                                "#st", "status"
                                        ))
                                        .expressionAttributeValues(Map.of(
                                                ":expected", AttributeValue.fromS(expectedStatus),
                                                ":newStatus", AttributeValue.fromS(newStatus),
                                                ":now", AttributeValue.fromN(String.valueOf(now))
                                        ))
                                        .build())
                                .build()
                )
                .build());
    }

    /**
     * Handles a transition transaction failure (race condition recovery).
     *
     * When two concurrent requests try the same transition, one wins the
     * DynamoDB conditional write and the other gets TransactionCanceledException.
     * We re-read the reservation:
     *   - If it now matches the target state → a concurrent request did the
     *     same transition → return the reservation as idempotent success.
     *   - Otherwise → throw an appropriate error.
     */
    private Reservation handleTransitionConflict(TransactionCanceledException e,
                                                  String reservationId, String targetStatus) {
        List<CancellationReason> reasons = e.cancellationReasons();

        // Re-read the reservation to see what state it is actually in
        Reservation current = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Reservation disappeared during transition: " + reservationId));

        // If a concurrent request already achieved the target → idempotent
        if (current.getStatus().equals(targetStatus)) {
            log.info("Transition conflict resolved as idempotent: {} is now {}", reservationId, targetStatus);
            return current;
        }

        // If the terminal state was reached by a different operation → idempotent
        if (configLoader.isTerminalState(current.getStatus())) {
            log.info("Transition conflict: {} is now terminal ({})", reservationId, current.getStatus());
            return current;
        }

        // Distinguish between capacity failure and status mismatch
        if (!reasons.isEmpty() && isConditionalCheckFailed(reasons.get(0))) {
            throw new InsufficientCapacityException(
                    "Capacity condition failed during " + targetStatus + " transition");
        }

        throw new InvalidTransitionException(
                "Concurrent modification: reservation " + reservationId
                + " is now " + current.getStatus()
                + ", cannot transition to " + targetStatus);
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private static boolean isConditionalCheckFailed(CancellationReason reason) {
        return reason != null && "ConditionalCheckFailed".equals(reason.code());
    }
}
