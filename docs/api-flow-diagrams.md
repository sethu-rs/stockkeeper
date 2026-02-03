# StockKeeper API Flow Diagrams

This document provides Mermaid flow diagrams documenting API operations, validation, state transitions, and DynamoDB mutations.

---

## Architecture Context

### AWS Infrastructure & Disaster Recovery

The StockKeeper system is deployed in an **active–passive multi-region AWS architecture**. For the complete infrastructure topology, disaster recovery strategy, and regional failover design, refer to the AWS Architecture Diagram below.

![AWS Architecture Diagram](./images/stockkeeper-aws-architecture.png)

#### Deployment Topology Summary

| Aspect | Primary Region (Active) | Secondary Region (Passive) |
|--------|-------------------------|----------------------------|
| **Traffic** | All read/write operations | No traffic until failover |
| **EKS Services** | Fully scaled, processing requests | Cold standby (scaled to 0) |
| **DynamoDB** | Global Tables (active writes) | Global Tables (read-only replica) |
| **Failover** | — | Promoted via Route 53 health checks |

#### Separation of Concerns

| Diagram Type | Scope | Covers |
|--------------|-------|--------|
| **AWS Architecture Diagram** | Infrastructure layer | Regions, VPCs, EKS, DynamoDB Global Tables, Route 53, DR strategy, failover |
| **Mermaid Flow Diagrams** (this document) | Application layer | API operations, validation logic, state transitions, Policy Engine, DynamoDB mutations |

---

## 1. System Architecture Overview

**All product catalog data and policy rules are loaded into memory at startup; no runtime network calls are made to external services for validation or policy decisions.**

```mermaid
flowchart TB
    subgraph Region["Primary Region (Active)"]
    direction TB

    subgraph Client["Client Layer"]
        API[REST API Requests]
    end

    subgraph App["Application Layer"]
        Controller[StockController]
        Validator["Bean Validation<br/>+ Custom Validators"]
        Service[StockService]
    end

    subgraph InMemory["In-Memory Product Catalog & Policy Engine"]
    
        ConfigFile[("capacity-config.yml")]
        ProductCatalog["Product Catalog:<br/>Capacity Types, Class Flags,<br/>Handling Rules"]
        PolicyEngine["Policy Engine:<br/>Eligibility, Transitions,<br/>Constraints"]
        ConfigFile --> ProductCatalog --> PolicyEngine
    end

    subgraph DynamoDB["DynamoDB (State & Persistence Only)"]
        Tables[("CapacityStock + Reservations")]
        Transaction["TransactWriteItems"]
    end

    API --> Controller --> Validator --> Service
    Service -->|"1. Evaluate Policy"| PolicyEngine
    Service -->|"2. Mutate State"| Transaction --> Tables

    end

    style PolicyEngine fill:#e8f5e9
    style ProductCatalog fill:#fff8e1
    style Region fill:#f5f5f5,stroke:#1976d2,stroke-width:2px
```

> **Key Insight:** Product rules and policy are enforced in memory before any DynamoDB write. DynamoDB is used only for state persistence, quantity mutations, and idempotency checks.

---

## 2. HOLD Operation Flow

The HOLD operation creates a new reservation. It's the only operation that creates a reservation record; all others transition existing reservations.

```mermaid
flowchart TD
    Start([POST /stocks/hold]) --> Validate{Bean Validation}
    Validate -->|Invalid| Err400[400 Bad Request]
    Validate -->|Valid| GenKeys[Generate Deterministic Keys]

    GenKeys --> CheckStock{Stock Exists?}
    CheckStock -->|No| Err404[404 Not Found]
    CheckStock -->|Yes| Policy

    subgraph Policy["Policy Engine (In-Memory)"]
        PE1{Class Flag OK?} -->|No| ErrP1[400 Invalid Flag]
        PE1 -->|Yes| PE2{Capacity Type OK?}
        PE2 -->|No| ErrP2[400 Invalid Type]
        PE2 -->|Yes| Pass[✓ Policy Pass]
    end

    Pass --> Transaction

    subgraph Transaction["TransactWriteItems (Atomic)"]
        T1["Update Stock: available -= qty, held += qty<br/>Condition: available_capacity >= qty"]
        T2["Put Reservation: status=HELD<br/>Condition: attribute_not_exists(reservation_id)"]
    end

    Transaction --> Result{Result}
    Result -->|Success| OK200[200 OK]
    Result -->|Reservation Exists| Idemp[200 OK isIdempotent=true]
    Result -->|Capacity Failed| Err409[409 Insufficient Capacity]

    style Policy fill:#e8f5e9
    style Idemp fill:#c8e6c9
```

### Key Generation Pattern

| Input Field | Generated Key |
|-------------|---------------|
| flightId + departureDate | `stockPk = FLIGHT#<flightId>#<departureDate>` |
| departureDatetime | `stockSk = WINDOW#<departureDatetime>` |
| shipmentId + capacityType + stockPk + stockSk | `reservationId = RESV#<shipmentId>#<capacityType>#<pk>#<sk>` |

---

## 3. State Transition Operations (COMMIT / LOAD / RELEASE)

All transition operations follow the same pattern: read reservation → validate transition → atomic update. The only differences are the source/target states and capacity buckets.

```mermaid
flowchart TD
    Start(["POST /stocks/{commit|load|release}"]) --> Validate{Validation}
    Validate -->|Invalid| Err400[400 Bad Request]
    Validate -->|Valid| Read[Read Reservation]

    Read -->|Not Found| Err404[404 Not Found]
    Read -->|Found| CheckState{Current State?}

    CheckState -->|Already Target/Terminal| Idemp[200 OK isIdempotent=true]
    CheckState -->|Valid Source| ValidateTrans{Transition Allowed?<br/>in capacity-config.yml}

    ValidateTrans -->|No| Err422[422 Invalid Transition]
    ValidateTrans -->|Yes| Transaction

    subgraph Transaction["TransactWriteItems (Atomic)"]
        T1["Update Stock:<br/>fromBucket -= qty, toBucket += qty<br/>Condition: fromBucket >= qty"]
        T2["Update Reservation:<br/>status = targetState<br/>Condition: status = expectedState"]
    end

    Transaction --> Result{Result}
    Result -->|Success| OK200[200 OK]
    Result -->|Conflict| ReRead[Re-read Reservation]
    ReRead -->|Target State| IdemConc[200 OK isIdempotent=true]
    ReRead -->|Other| Err409[409 Concurrent Modification]

    style Idemp fill:#c8e6c9
    style IdemConc fill:#c8e6c9
```

### Operation-Specific Details

| Operation | Valid From | Target State | Capacity Movement |
|-----------|------------|--------------|-------------------|
| **COMMIT** | HELD | COMMITTED | held → committed |
| **LOAD** | COMMITTED | LOADED | committed → loaded |
| **RELEASE** | HELD, COMMITTED, LOADED | RELEASED | any → available |

### DynamoDB Condition Expressions

| Operation | Stock Condition | Reservation Condition |
|-----------|-----------------|----------------------|
| HOLD | `available_capacity >= :qty` | `attribute_not_exists(reservation_id)` |
| COMMIT | `held_capacity >= :qty` | `status = 'HELD'` |
| LOAD | `committed_capacity >= :qty` | `status = 'COMMITTED'` |
| RELEASE | `<source>_capacity >= :qty` | `status = :currentStatus` |

---

## 4. State Machine & Transitions

```mermaid
stateDiagram-v2
    [*] --> HELD : HOLD
    HELD --> COMMITTED : COMMIT
    HELD --> RELEASED : RELEASE
    COMMITTED --> LOADED : LOAD
    COMMITTED --> RELEASED : RELEASE
    LOADED --> RELEASED : RELEASE
    RELEASED --> [*]

    note right of HELD : held_capacity (TTL enforced)
    note right of COMMITTED : committed_capacity (firm)
    note right of LOADED : loaded_capacity (physical)
    note right of RELEASED : → available_capacity (terminal)
```

| From State | To States | Capacity Bucket |
|------------|-----------|-----------------|
| — | HELD | held_capacity |
| HELD | COMMITTED, RELEASED | → committed or → available |
| COMMITTED | LOADED, RELEASED | → loaded or → available |
| LOADED | RELEASED | → available |
| RELEASED | (terminal) | — |

---

## 5. Idempotency Handling

All operations are idempotent through deterministic keys and conditional writes.

```mermaid
flowchart TD
    Fail[Transaction Failed] --> Type{Operation?}

    Type -->|HOLD| HoldPath{"reasons[1] =<br/>ConditionalCheckFailed?"}
    HoldPath -->|Yes| HoldIdem["Idempotent: Return existing reservation"]
    HoldPath -->|No| CapPath{"reasons[0] =<br/>ConditionalCheckFailed?"}
    CapPath -->|Yes| Cap409[409 Insufficient Capacity]
    CapPath -->|No| Err500[500 Unexpected]

    Type -->|COMMIT/LOAD/RELEASE| ReRead[Re-read Reservation]
    ReRead --> State{Current State?}
    State -->|Target or Terminal| TransIdem["Idempotent: Concurrent request won"]
    State -->|Other| Conc409[409 Concurrent Modification]

    style HoldIdem fill:#c8e6c9
    style TransIdem fill:#c8e6c9
```

### Idempotency Strategy

| Mechanism | How It Works |
|-----------|--------------|
| **Deterministic Keys** | Same business fields → same reservationId (no random UUIDs) |
| **Conditional Writes** | HOLD: `attribute_not_exists`, Transitions: `status = expected` |
| **Read-After-Conflict** | On failure, re-read; if target state reached → idempotent success |

---

## 6. Product Catalog & Policy Engine

The Product Catalog is loaded from `capacity-config.yml` at startup and kept **in memory**. The Policy Engine enforces all business rules **before** any DynamoDB operation.

```mermaid
flowchart LR
    subgraph Startup["Startup (Once)"]
        Config[(capacity-config.yml)]
    end

    subgraph InMemory["In-Memory (No I/O)"]
        Catalog["Product Catalog"]
        Engine["Policy Engine"]
    end

    subgraph Checks["Policy Checks"]
        C1["① Class Flag Eligible?"]
        C2["② Capacity Type Valid?"]
        C3["③ Transition Allowed?"]
        C4["④ Constraints Met?"]
    end

    subgraph Result["Decision"]
        Pass["✓ PASS → DynamoDB"]
        Fail["✗ FAIL → 4xx Error"]
    end

    Config --> Catalog --> Engine --> Checks
    Checks --> Pass
    Checks --> Fail

    style Catalog fill:#fff8e1
    style Engine fill:#e8f5e9
    style Pass fill:#c8e6c9
    style Fail fill:#ffcdd2
```

### Product Categories (from capacity-config.yml)

| Capacity Type | Allowed Class Flags | Unit | Max Hold |
|---------------|---------------------|------|----------|
| FLIGHT | GENERAL, PRIORITY, EXPRESS, DANGEROUS_GOODS | KG | 60 min |
| WAREHOUSE | GENERAL, COLD_STORAGE, HAZMAT, OVERSIZED | CBM | 120 min |
| ULD | GENERAL, PRIORITY, TEMPERATURE_CONTROLLED | KG | 90 min |

### Policy Engine Guarantees

- **Deterministic** — Purely config-driven, no AI/ML
- **No Network I/O** — All checks in application memory
- **Fail Fast** — Invalid requests rejected before DynamoDB
- **Single Source of Truth** — All rules from `capacity-config.yml`

---

## Quick Reference

### API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/stocks/hold` | POST | Reserve capacity (→ HELD) |
| `/stocks/commit` | POST | Confirm reservation (→ COMMITTED) |
| `/stocks/load` | POST | Mark as loaded (→ LOADED) |
| `/stocks/release` | POST | Release reservation (→ RELEASED) |
| `/stocks` | GET | List/query stock items |
| `/stocks/{pk}/{sk}` | GET | Get single stock item |

### HTTP Response Codes

| Code | Meaning |
|------|---------|
| 200 | Success (check `isIdempotent` flag) |
| 400 | Validation error / Policy violation |
| 404 | Stock or reservation not found |
| 409 | Insufficient capacity or concurrent modification |
| 422 | Invalid state transition |
