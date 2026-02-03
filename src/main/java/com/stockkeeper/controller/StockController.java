package com.stockkeeper.controller;

import com.stockkeeper.model.dto.CapacityStockResponse;
import com.stockkeeper.model.dto.CommitStockRequest;
import com.stockkeeper.model.dto.CommitStockResponse;
import com.stockkeeper.model.dto.ErrorResponse;
import com.stockkeeper.model.dto.HoldStockRequest;
import com.stockkeeper.model.dto.HoldStockResponse;
import com.stockkeeper.model.dto.LoadStockRequest;
import com.stockkeeper.model.dto.LoadStockResponse;
import com.stockkeeper.model.dto.ReleaseStockRequest;
import com.stockkeeper.model.dto.ReleaseStockResponse;
import com.stockkeeper.service.StockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for stock management operations.
 *
 * This controller is intentionally thin — all business logic, idempotency
 * handling, and DynamoDB interactions live in {@link StockService}.
 *
 * HTTP semantics:
 *   200 → success (including idempotent retries)
 *   400 → validation error (checked before DynamoDB access)
 *   404 → stock or reservation not found
 *   409 → insufficient capacity or invalid state transition
 */
@RestController
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    // ═══════════════════════════════════════════════════════════════════════
    // Command endpoints (state transitions)
    // ═══════════════════════════════════════════════════════════════════════

    @Tag(name = "Stock Commands")
    @Operation(
            summary = "Hold capacity for a shipment",
            description = """
                    Command operation that atomically reserves capacity on a CapacityStock item.

                    **This is NOT a simple create** — it is an idempotent command. The reservation_id \
                    is derived deterministically from the request fields \
                    (`RESV#<shipment_id>#<capacity_type>#<stock_pk>#<stock_sk>`). \
                    There are no client-provided idempotency keys or random UUIDs.

                    **Transaction (DynamoDB TransactWriteItems):**
                    - Update CapacityStock: `available_capacity -= qty`, `held_capacity += qty` \
                      (condition: `available_capacity >= qty`)
                    - Put Reservation: `status = HELD` \
                      (condition: `attribute_not_exists(reservation_id)`)

                    **Idempotency:** Sending the exact same request again returns **200** with \
                    `idempotent: true` and the existing reservation — capacity is NOT modified a second time.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Hold succeeded or idempotent retry",
                    content = @Content(schema = @Schema(implementation = HoldStockResponse.class),
                            examples = @ExampleObject(name = "New hold", value = """
                                    {
                                      "reservation": {
                                        "reservation_id": "RESV#SHP-001#FLIGHT#FLIGHT#BA-2173#2025-08-15#WINDOW#2025-08-15T14:30:00Z",
                                        "shipment_id": "SHP-001",
                                        "capacity_type": "FLIGHT",
                                        "stock_pk": "FLIGHT#BA-2173#2025-08-15",
                                        "stock_sk": "WINDOW#2025-08-15T14:30:00Z",
                                        "quantity": 500,
                                        "class_flag": "GENERAL",
                                        "priority_level": 1,
                                        "status": "HELD",
                                        "created_at": 1723728000,
                                        "updated_at": 1723728000,
                                        "expiry_ts": 1723731600
                                      },
                                      "idempotent": false
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Validation error — capacity type, class flag, quantity, or required key fields invalid",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Stock item not found for the derived pk/sk",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Insufficient available capacity",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(examples = @ExampleObject(name = "FLIGHT hold", value = """
                    {
                      "shipment_id": "SHP-001",
                      "capacity_type": "FLIGHT",
                      "requested_quantity": 500,
                      "class_flag": "GENERAL",
                      "priority_level": 1,
                      "flight_id": "BA-2173",
                      "departure_date": "2025-08-15",
                      "departure_datetime": "2025-08-15T14:30:00Z"
                    }""")))
    @PostMapping("/stock/hold")
    public ResponseEntity<HoldStockResponse> holdStock(
            @Valid @RequestBody HoldStockRequest request) {
        return ResponseEntity.ok(stockService.holdStock(request));
    }

    @Tag(name = "Stock Commands")
    @Operation(
            summary = "Commit a held reservation (HELD -> COMMITTED)",
            description = """
                    Transitions a reservation from HELD to COMMITTED, moving capacity from \
                    `held_capacity` to `committed_capacity`.

                    The reservation is identified by the same business fields used during hold — \
                    the deterministic reservation_id is re-derived, not passed by the client.

                    **Idempotency:** If the reservation is already COMMITTED or in a terminal state \
                    (RELEASED), returns **200** with `idempotent: true`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Commit succeeded or idempotent retry",
                    content = @Content(schema = @Schema(implementation = CommitStockResponse.class),
                            examples = @ExampleObject(name = "Committed", value = """
                                    {
                                      "reservation": {
                                        "reservation_id": "RESV#SHP-001#FLIGHT#FLIGHT#BA-2173#2025-08-15#WINDOW#2025-08-15T14:30:00Z",
                                        "status": "COMMITTED",
                                        "quantity": 500,
                                        "capacity_type": "FLIGHT"
                                      },
                                      "idempotent": false
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Reservation not found (hold was never executed)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Invalid transition (reservation is not in HELD state)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(examples = @ExampleObject(name = "Commit FLIGHT reservation", value = """
                    {
                      "shipment_id": "SHP-001",
                      "capacity_type": "FLIGHT",
                      "flight_id": "BA-2173",
                      "departure_date": "2025-08-15",
                      "departure_datetime": "2025-08-15T14:30:00Z"
                    }""")))
    @PostMapping("/stock/commit")
    public ResponseEntity<CommitStockResponse> commitStock(
            @Valid @RequestBody CommitStockRequest request) {
        return ResponseEntity.ok(stockService.commitStock(request));
    }

    @Tag(name = "Stock Commands")
    @Operation(
            summary = "Load a committed reservation (COMMITTED -> LOADED)",
            description = """
                    Transitions a reservation from COMMITTED to LOADED, moving capacity from \
                    `committed_capacity` to `loaded_capacity`. Represents physical loading.

                    **Idempotency:** If the reservation is already LOADED or in a terminal state, \
                    returns **200** with `idempotent: true`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Load succeeded or idempotent retry",
                    content = @Content(schema = @Schema(implementation = LoadStockResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Reservation not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Invalid transition (reservation is not in COMMITTED state)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(examples = @ExampleObject(name = "Load FLIGHT reservation", value = """
                    {
                      "shipment_id": "SHP-001",
                      "capacity_type": "FLIGHT",
                      "flight_id": "BA-2173",
                      "departure_date": "2025-08-15",
                      "departure_datetime": "2025-08-15T14:30:00Z"
                    }""")))
    @PostMapping("/stock/load")
    public ResponseEntity<LoadStockResponse> loadStock(
            @Valid @RequestBody LoadStockRequest request) {
        return ResponseEntity.ok(stockService.loadStock(request));
    }

    @Tag(name = "Stock Commands")
    @Operation(
            summary = "Release a reservation (HELD/COMMITTED/LOADED -> RELEASED)",
            description = """
                    Releases capacity back to `available_capacity` from whichever bucket it \
                    currently occupies:
                    - HELD -> released from `held_capacity`
                    - COMMITTED -> released from `committed_capacity`
                    - LOADED -> released from `loaded_capacity`

                    RELEASED is a terminal state — no further transitions are possible.

                    **Idempotency:** If the reservation is already RELEASED, returns **200** with \
                    `idempotent: true`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Release succeeded or idempotent retry",
                    content = @Content(schema = @Schema(implementation = ReleaseStockResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Reservation not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Invalid transition",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(examples = @ExampleObject(name = "Release FLIGHT reservation", value = """
                    {
                      "shipment_id": "SHP-001",
                      "capacity_type": "FLIGHT",
                      "flight_id": "BA-2173",
                      "departure_date": "2025-08-15",
                      "departure_datetime": "2025-08-15T14:30:00Z"
                    }""")))
    @PostMapping("/stock/release")
    public ResponseEntity<ReleaseStockResponse> releaseStock(
            @Valid @RequestBody ReleaseStockRequest request) {
        return ResponseEntity.ok(stockService.releaseStock(request));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Query endpoints (read-only)
    // ═══════════════════════════════════════════════════════════════════════

    @Tag(name = "Stock Queries")
    @Operation(
            summary = "List capacity stock entries",
            description = """
                    Returns a list of CapacityStock items with optional filtering.

                    **Filter priority:**
                    1. `pk` provided -> efficient DynamoDB Query on partition key
                    2. `capacity_type` provided -> Scan with filter
                    3. No filters -> full table Scan

                    Note: `pk` values contain `#` characters (e.g. `FLIGHT#BA-2173#2025-08-15`) \
                    which must be URL-encoded as `%23` in the query string.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of stock items (may be empty)",
                    content = @Content(schema = @Schema(implementation = CapacityStockResponse.class)))
    })
    @GetMapping("/stocks")
    public ResponseEntity<List<CapacityStockResponse>> listStocks(
            @Parameter(description = "Filter by capacity type", example = "FLIGHT",
                    schema = @Schema(allowableValues = {"FLIGHT", "WAREHOUSE", "ULD"}))
            @RequestParam(value = "capacity_type", required = false) String capacityType,

            @Parameter(description = "Filter by partition key (URL-encode # as %23)",
                    example = "FLIGHT%23BA-2173%232025-08-15")
            @RequestParam(value = "pk", required = false) String pk) {
        return ResponseEntity.ok(stockService.listStocks(capacityType, pk));
    }

    @Tag(name = "Stock Queries")
    @Operation(
            summary = "Get a single stock item by composite key",
            description = """
                    Returns a single CapacityStock record identified by its partition key (pk) \
                    and sort key (sk).

                    Both path variables must be **URL-encoded** because they contain `#` characters.

                    Example: `GET /stocks/FLIGHT%23BA-2173%232025-08-15/WINDOW%232025-08-15T14%3A30%3A00Z`

                    Spring automatically URL-decodes the values before passing them to the handler.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stock item found",
                    content = @Content(schema = @Schema(implementation = CapacityStockResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "pk": "FLIGHT#BA-2173#2025-08-15",
                                      "sk": "WINDOW#2025-08-15T14:30:00Z",
                                      "capacity_type": "FLIGHT",
                                      "total_capacity": 10000,
                                      "available_capacity": 9500,
                                      "held_capacity": 500,
                                      "committed_capacity": 0,
                                      "loaded_capacity": 0,
                                      "unit_of_measure": "KG",
                                      "class_flags": ["GENERAL", "PRIORITY", "EXPRESS"],
                                      "priority_level": 1,
                                      "expiry_time": 1723814400
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "Stock item not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/stocks/{pk}/{sk}")
    public ResponseEntity<CapacityStockResponse> getStock(
            @Parameter(description = "Partition key (URL-encoded)", example = "FLIGHT%23BA-2173%232025-08-15")
            @PathVariable String pk,
            @Parameter(description = "Sort key (URL-encoded)", example = "WINDOW%232025-08-15T14%3A30%3A00Z")
            @PathVariable String sk) {
        return ResponseEntity.ok(stockService.getStock(pk, sk));
    }
}
