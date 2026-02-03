# StockKeeper API Flow Diagrams

This document provides comprehensive Mermaid flow diagrams documenting all API operations, including validation, conditional checks, and DynamoDB mutations.

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

> **Note:** All Mermaid diagrams in this document represent logical flows executing within the **active (primary) region**. Disaster recovery and cross-region replication are handled at the infrastructure layer and are not repeated in individual operation flows.

---

## 1. System Architecture Overview

*Deployed in an active–passive multi-region AWS architecture; flows shown assume the active (primary) region.*

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
        ConfigLoader["CapacityConfigLoader<br/>@PostConstruct"]

        subgraph ProductCatalog["Product Catalog"]
            Categories["Product Categories:<br/>GENERAL, PRIORITY, EXPRESS<br/>DANGEROUS_GOODS, COLD_STORAGE<br/>HAZMAT, TEMPERATURE_CONTROLLED"]
            HandlingRules["Handling Rules:<br/>maxHoldDurationMinutes<br/>unitOfMeasure (KG, CBM)"]
            CapTypes["Capacity Types:<br/>FLIGHT, WAREHOUSE, ULD"]
        end

        subgraph PolicyEngine["Policy Engine (Deterministic)"]
            PE1["Class Flag Eligibility"]
            PE2["Capacity Type Validation"]
            PE3["Transition Rules"]
            PE4["Handling Constraints"]
        end

        ConfigFile --> ConfigLoader
        ConfigLoader --> ProductCatalog
        ProductCatalog --> PolicyEngine
    end

    subgraph Keys["Key Generation"]
        KeyGen[StockKeyGenerator]
        KeyPatterns["Deterministic Keys:<br/>pk: FLIGHT#id#date<br/>sk: WINDOW#datetime<br/>reservationId: RESV#..."]
    end

    subgraph DynamoDB["DynamoDB Layer (State & Persistence Only)"]
        StockTable[("CapacityStock Table")]
        ResvTable[("Reservations Table")]
        Transaction["TransactWriteItems<br/>Atomic Mutations"]
    end

    API --> Controller
    Controller --> Validator
    Validator --> Service
    Service -->|"1. Evaluate Policy"| PolicyEngine
    PolicyEngine -->|"Policy Pass"| KeyGen
    Service --> KeyGen
    KeyGen --> KeyPatterns
    Service -->|"2. Mutate State"| Transaction
    Transaction --> StockTable
    Transaction --> ResvTable

    end

    style PolicyEngine fill:#e8f5e9
    style ProductCatalog fill:#fff8e1
    style Region fill:#f5f5f5,stroke:#1976d2,stroke-width:2px
```

> **Key Insight:** Product rules and policy are enforced in memory before any DynamoDB write. DynamoDB is used only for state persistence, quantity mutations, and idempotency checks.

---

## 2. HOLD Operation Flow

The HOLD operation reserves capacity for a shipment. It uses `TransactWriteItems` with two conditions for atomicity and idempotency.

> **Policy Engine is consulted BEFORE any DynamoDB mutation.** All business rules are enforced in memory.

```mermaid
flowchart TD
    Start([POST /stocks/hold]) --> Validate{Bean Validation<br/>+ Custom Validators}
    Validate -->|Invalid| Err400[400 Bad Request]
    Validate -->|Valid| GenKeys[Generate Deterministic Keys<br/>stockPk, stockSk, reservationId]

    GenKeys --> CheckStock{Stock Exists?<br/>stockRepository.findByPkAndSk}
    CheckStock -->|Not Found| Err404[404 Stock Not Found]
    CheckStock -->|Found| PolicyCheck

    subgraph PolicyCheck["Policy Engine Evaluation (In-Memory)"]
        PE1{Class Flag Eligible?<br/>classFlag ∈ allowedClassFlags<br/>for capacityType}
        PE1 -->|No| ErrPolicy1[400 Invalid Class Flag<br/>for Capacity Type]
        PE1 -->|Yes| PE2{Capacity Type Valid?<br/>capacityType ∈ capacityTypes}
        PE2 -->|No| ErrPolicy2[400 Unsupported<br/>Capacity Type]
        PE2 -->|Yes| PE3{Handling Constraints?<br/>unitOfMeasure compatible}
        PE3 -->|No| ErrPolicy3[400 Handling<br/>Constraint Violation]
        PE3 -->|Yes| PolicyPass[Policy Passed ✓]
    end

    PolicyPass --> BuildResv[Build Reservation Object<br/>status=HELD<br/>expiryTs=now + maxHoldMinutes]

    BuildResv --> Transaction[TransactWriteItems]

    style PolicyCheck fill:#e8f5e9

    subgraph TxItems["Transaction Items"]
        Item0["[0] Update CapacityStock<br/>Condition: available_capacity >= qty<br/>Update: available -= qty, held += qty"]
        Item1["[1] Put Reservation<br/>Condition: attribute_not_exists(reservation_id)<br/>Item: new reservation with status=HELD"]
    end

    Transaction --> TxItems
    TxItems --> TxResult{Transaction Result}

    TxResult -->|Success| Return200[200 OK<br/>Return new reservation<br/>isIdempotent=false]

    TxResult -->|TransactionCanceledException| Analyze[Analyze CancellationReasons]

    Analyze --> CheckPut{"reasons[1]<br/>ConditionalCheckFailed?"}
    CheckPut -->|Yes| Idempotent[Reservation already exists<br/>Fetch existing reservation]
    Idempotent --> Return200Idemp[200 OK<br/>Return existing reservation<br/>isIdempotent=true]

    CheckPut -->|No| CheckUpdate{"reasons[0]<br/>ConditionalCheckFailed?"}
    CheckUpdate -->|Yes| Err409[409 Insufficient Capacity]
    CheckUpdate -->|No| Err500[500 Unexpected Error]

    style Item0 fill:#e1f5fe
    style Item1 fill:#fff3e0
    style Idempotent fill:#c8e6c9
    style Return200Idemp fill:#c8e6c9
```

### Key Generation Pattern (HOLD)

```mermaid
flowchart LR
    subgraph Input["Request Fields"]
        Ship[shipmentId]
        Type[capacityType]
        Flight[flightId]
        Date[departureDate]
        DT[departureDatetime]
    end

    subgraph Output["Generated Keys"]
        PK["stockPk = FLIGHT#flightId#departureDate"]
        SK["stockSk = WINDOW#departureDatetime"]
        RID["reservationId = RESV#shipmentId#<br/>capacityType#stockPk#stockSk"]
    end

    Ship --> RID
    Type --> PK
    Type --> RID
    Flight --> PK
    Date --> PK
    DT --> SK
    PK --> RID
    SK --> RID
```

---

## 3. COMMIT Operation Flow

COMMIT transitions a reservation from HELD to COMMITTED, moving capacity from `held_capacity` to `committed_capacity`.

```mermaid
flowchart TD
    Start([POST /stocks/commit]) --> Validate{Bean Validation}
    Validate -->|Invalid| Err400[400 Bad Request]
    Validate -->|Valid| GenKeys[Generate reservationId<br/>from business fields]

    GenKeys --> ReadResv[Read Reservation<br/>reservationRepository.findById]
    ReadResv -->|Not Found| Err404[404 Reservation Not Found]
    ReadResv -->|Found| CheckState{Current Status?}

    CheckState -->|COMMITTED| IdemReturn[200 OK<br/>isIdempotent=true]
    CheckState -->|RELEASED| IdemReturn
    CheckState -->|HELD| ValidateTrans{Transition Allowed?<br/>HELD → COMMITTED<br/>in capacity-config.yml}

    ValidateTrans -->|No| Err422[422 Invalid Transition]
    ValidateTrans -->|Yes| Transaction[TransactWriteItems]

    subgraph TxItems["Transaction Items"]
        Item0["[0] Update CapacityStock<br/>Condition: held_capacity >= qty<br/>Update: held -= qty, committed += qty"]
        Item1["[1] Update Reservation<br/>Condition: status = 'HELD'<br/>Update: status = 'COMMITTED', updated_at = now"]
    end

    Transaction --> TxItems
    TxItems --> TxResult{Transaction Result}

    TxResult -->|Success| Return200[200 OK<br/>status=COMMITTED<br/>isIdempotent=false]

    TxResult -->|TransactionCanceledException| ReRead[Re-read Reservation]
    ReRead --> CheckNew{New Status?}
    CheckNew -->|COMMITTED| IdemResolve[200 OK<br/>Concurrent request succeeded<br/>isIdempotent=true]
    CheckNew -->|RELEASED| IdemResolve
    CheckNew -->|Other| Err409[409 Concurrent Modification]

    style Item0 fill:#e1f5fe
    style Item1 fill:#fff3e0
    style IdemReturn fill:#c8e6c9
    style IdemResolve fill:#c8e6c9
```

---

## 4. LOAD Operation Flow

LOAD transitions a reservation from COMMITTED to LOADED, moving capacity from `committed_capacity` to `loaded_capacity`.

```mermaid
flowchart TD
    Start([POST /stocks/load]) --> Validate{Bean Validation}
    Validate -->|Invalid| Err400[400 Bad Request]
    Validate -->|Valid| GenKeys[Generate reservationId<br/>from business fields]

    GenKeys --> ReadResv[Read Reservation<br/>reservationRepository.findById]
    ReadResv -->|Not Found| Err404[404 Reservation Not Found]
    ReadResv -->|Found| CheckState{Current Status?}

    CheckState -->|LOADED| IdemReturn[200 OK<br/>isIdempotent=true]
    CheckState -->|RELEASED| IdemReturn
    CheckState -->|COMMITTED| ValidateTrans{Transition Allowed?<br/>COMMITTED → LOADED<br/>in capacity-config.yml}

    ValidateTrans -->|No| Err422[422 Invalid Transition]
    ValidateTrans -->|Yes| Transaction[TransactWriteItems]

    subgraph TxItems["Transaction Items"]
        Item0["[0] Update CapacityStock<br/>Condition: committed_capacity >= qty<br/>Update: committed -= qty, loaded += qty"]
        Item1["[1] Update Reservation<br/>Condition: status = 'COMMITTED'<br/>Update: status = 'LOADED', updated_at = now"]
    end

    Transaction --> TxItems
    TxItems --> TxResult{Transaction Result}

    TxResult -->|Success| Return200[200 OK<br/>status=LOADED<br/>isIdempotent=false]

    TxResult -->|TransactionCanceledException| ReRead[Re-read Reservation]
    ReRead --> CheckNew{New Status?}
    CheckNew -->|LOADED| IdemResolve[200 OK<br/>Concurrent request succeeded<br/>isIdempotent=true]
    CheckNew -->|RELEASED| IdemResolve
    CheckNew -->|Other| Err409[409 Concurrent Modification]

    style Item0 fill:#e1f5fe
    style Item1 fill:#fff3e0
    style IdemReturn fill:#c8e6c9
    style IdemResolve fill:#c8e6c9
```

---

## 5. RELEASE Operation Flow

RELEASE can be called from any non-terminal state (HELD, COMMITTED, LOADED). It returns capacity to `available_capacity`.

```mermaid
flowchart TD
    Start([POST /stocks/release]) --> Validate{Bean Validation}
    Validate -->|Invalid| Err400[400 Bad Request]
    Validate -->|Valid| GenKeys[Generate reservationId<br/>from business fields]

    GenKeys --> ReadResv[Read Reservation<br/>reservationRepository.findById]
    ReadResv -->|Not Found| Err404[404 Reservation Not Found]
    ReadResv -->|Found| CheckState{Current Status?}

    CheckState -->|RELEASED| IdemReturn[200 OK<br/>Already terminal<br/>isIdempotent=true]

    CheckState -->|HELD| FromHeld[fromBucket = held_capacity]
    CheckState -->|COMMITTED| FromCommitted[fromBucket = committed_capacity]
    CheckState -->|LOADED| FromLoaded[fromBucket = loaded_capacity]

    FromHeld --> Transaction
    FromCommitted --> Transaction
    FromLoaded --> Transaction

    Transaction[TransactWriteItems]

    subgraph TxItems["Transaction Items"]
        Item0["[0] Update CapacityStock<br/>Condition: fromBucket >= qty<br/>Update: fromBucket -= qty, available += qty"]
        Item1["[1] Update Reservation<br/>Condition: status = currentStatus<br/>Update: status = 'RELEASED', updated_at = now"]
    end

    Transaction --> TxItems
    TxItems --> TxResult{Transaction Result}

    TxResult -->|Success| Return200[200 OK<br/>status=RELEASED<br/>isIdempotent=false]

    TxResult -->|TransactionCanceledException| ReRead[Re-read Reservation]
    ReRead --> CheckNew{New Status?}
    CheckNew -->|RELEASED| IdemResolve[200 OK<br/>Concurrent release succeeded<br/>isIdempotent=true]
    CheckNew -->|Other| Err409[409 Concurrent Modification]

    style Item0 fill:#e1f5fe
    style Item1 fill:#fff3e0
    style IdemReturn fill:#c8e6c9
    style IdemResolve fill:#c8e6c9
```

### RELEASE Capacity Movement by Source State

```mermaid
flowchart LR
    subgraph From["Release From"]
        H[HELD State]
        C[COMMITTED State]
        L[LOADED State]
    end

    subgraph Buckets["Capacity Movement"]
        HB["held_capacity -= qty"]
        CB["committed_capacity -= qty"]
        LB["loaded_capacity -= qty"]
        AB["available_capacity += qty"]
    end

    H --> HB
    C --> CB
    L --> LB
    HB --> AB
    CB --> AB
    LB --> AB
```

---

## 6. State Machine Diagram

The complete reservation lifecycle with all valid transitions.

```mermaid
stateDiagram-v2
    [*] --> HELD : HOLD

    HELD --> COMMITTED : COMMIT
    HELD --> RELEASED : RELEASE

    COMMITTED --> LOADED : LOAD
    COMMITTED --> RELEASED : RELEASE

    LOADED --> RELEASED : RELEASE

    RELEASED --> [*]

    note right of HELD
        Capacity bucket: held_capacity
        Soft reservation
        TTL enforced
    end note

    note right of COMMITTED
        Capacity bucket: committed_capacity
        Firm booking
        No TTL
    end note

    note right of LOADED
        Capacity bucket: loaded_capacity
        Physically loaded
    end note

    note right of RELEASED
        Terminal state
        Capacity returned to available_capacity
        Explicit user/system action
    end note
```

### State Transition Matrix

| From State | Valid Transitions | Capacity Movement |
|------------|-------------------|-------------------|
| HELD | COMMITTED, RELEASED | held → committed, held → available |
| COMMITTED | LOADED, RELEASED | committed → loaded, committed → available |
| LOADED | RELEASED | loaded → available |
| RELEASED | (none - terminal) | - |

---

## 7. Idempotency Handling

### Overview: How Idempotency Works

```mermaid
flowchart TD
    subgraph Strategy["Idempotency Strategy"]
        D1[Deterministic Keys]
        D2[Conditional Writes]
        D3[Read-After-Conflict]
    end

    D1 --> Exp1["Same business fields<br/>= Same reservation_id<br/>No random UUIDs"]
    D2 --> Exp2["HOLD: attribute_not_exists<br/>Transitions: status = expected"]
    D3 --> Exp3["On conflict, re-read<br/>If target state reached<br/>= Idempotent success"]
```

### HOLD Idempotency Flow

```mermaid
sequenceDiagram
    participant C1 as Client (1st call)
    participant C2 as Client (retry)
    participant S as StockService
    participant D as DynamoDB

    Note over C1,D: First HOLD request
    C1->>S: holdStock(shipmentId=X, flightId=Y, ...)
    S->>S: Generate reservationId = RESV#X#...
    S->>D: TransactWriteItems<br/>[Put Reservation, Update Stock]
    D-->>S: Success
    S-->>C1: 200 OK, isIdempotent=false

    Note over C2,D: Retry (same fields → same reservationId)
    C2->>S: holdStock(shipmentId=X, flightId=Y, ...)
    S->>S: Generate reservationId = RESV#X#... (same!)
    S->>D: TransactWriteItems<br/>[Put Reservation, Update Stock]
    D-->>S: TransactionCanceledException<br/>Put: ConditionalCheckFailed
    S->>S: Check reasons[1] = ConditionalCheckFailed
    S->>D: GetItem(reservationId)
    D-->>S: Existing reservation
    S-->>C2: 200 OK, isIdempotent=true
```

### Transition Idempotency Flow

```mermaid
sequenceDiagram
    participant C1 as Client (1st call)
    participant C2 as Client (concurrent)
    participant S as StockService
    participant D as DynamoDB

    Note over C1,D: First COMMIT request
    C1->>S: commitStock(...)
    S->>D: GetItem(reservationId)
    D-->>S: status=HELD
    S->>D: TransactWriteItems<br/>[Update Stock, Update Reservation]

    Note over C2,D: Concurrent COMMIT (same reservation)
    C2->>S: commitStock(...)
    S->>D: GetItem(reservationId)
    D-->>S: status=HELD (stale read)
    S->>D: TransactWriteItems<br/>[Update Stock, Update Reservation]

    D-->>C1: Success
    D-->>S: TransactionCanceledException<br/>Condition failed

    S->>S: Re-read reservation
    S->>D: GetItem(reservationId)
    D-->>S: status=COMMITTED
    S-->>C2: 200 OK, isIdempotent=true<br/>(concurrent request already succeeded)
```

### Idempotency Decision Tree

```mermaid
flowchart TD
    Start[Transaction Failed] --> CheckOp{Operation Type?}

    CheckOp -->|HOLD| HoldCheck{"reasons[1]<br/>ConditionalCheckFailed?"}
    HoldCheck -->|Yes| HoldIdem["Idempotent Success<br/>Return existing reservation"]
    HoldCheck -->|No| CapCheck{"reasons[0]<br/>ConditionalCheckFailed?"}
    CapCheck -->|Yes| InsuffCap[409 Insufficient Capacity]
    CapCheck -->|No| Unknown[500 Unexpected Error]

    CheckOp -->|COMMIT/LOAD/RELEASE| ReRead[Re-read Reservation]
    ReRead --> StateCheck{Current State?}
    StateCheck -->|Target State| TransIdem["Idempotent Success<br/>Concurrent request won"]
    StateCheck -->|Terminal State| TransIdem
    StateCheck -->|Other| ConcMod[409 Concurrent Modification]

    style HoldIdem fill:#c8e6c9
    style TransIdem fill:#c8e6c9
```

---

## 8. DynamoDB Transaction Patterns

### TransactWriteItems Structure

```mermaid
flowchart TB
    subgraph Request["TransactWriteItemsRequest"]
        direction TB
        Items[transactItems: List]
    end

    subgraph HOLD["HOLD Transaction"]
        H0["Item[0]: Update CapacityStock"]
        H1["Item[1]: Put Reservation"]
    end

    subgraph Transition["COMMIT/LOAD/RELEASE Transaction"]
        T0["Item[0]: Update CapacityStock"]
        T1["Item[1]: Update Reservation"]
    end

    Items --> HOLD
    Items --> Transition

    subgraph H0Detail["CapacityStock Update (HOLD)"]
        H0C[Condition: available_capacity >= :qty]
        H0U[Update: available -= :qty, held += :qty]
    end

    subgraph H1Detail["Reservation Put (HOLD)"]
        H1C[Condition: attribute_not_exists reservation_id]
        H1I[Item: Full reservation object]
    end

    H0 --> H0Detail
    H1 --> H1Detail
```

### Condition Expression Patterns

| Operation | Item | Condition | Purpose |
|-----------|------|-----------|---------|
| HOLD | CapacityStock | `available_capacity >= :qty` | Ensure sufficient capacity |
| HOLD | Reservation | `attribute_not_exists(reservation_id)` | Prevent duplicate creates |
| COMMIT | CapacityStock | `held_capacity >= :qty` | Ensure capacity in correct bucket |
| COMMIT | Reservation | `#st = :expected` | Optimistic lock on status |
| LOAD | CapacityStock | `committed_capacity >= :qty` | Ensure capacity in correct bucket |
| LOAD | Reservation | `#st = :expected` | Optimistic lock on status |
| RELEASE | CapacityStock | `<bucket>_capacity >= :qty` | Ensure capacity in source bucket |
| RELEASE | Reservation | `#st = :expected` | Optimistic lock on status |

---

## 9. Configuration-Driven Validation & Product Catalog

The Product Catalog is loaded from `capacity-config.yml` at startup and kept **in memory**. The Policy Engine uses this catalog to enforce all business rules **before** any DynamoDB operation.

```mermaid
flowchart TD
    subgraph ConfigFile["capacity-config.yml (Source of Truth)"]
        subgraph CapTypes["Capacity Types & Product Categories"]
            FLIGHT["FLIGHT:<br/>allowedClassFlags: GENERAL, PRIORITY,<br/>EXPRESS, DANGEROUS_GOODS<br/>maxHoldDurationMinutes: 60<br/>unitOfMeasure: KG"]
            WAREHOUSE["WAREHOUSE:<br/>allowedClassFlags: GENERAL, COLD_STORAGE,<br/>HAZMAT, OVERSIZED<br/>maxHoldDurationMinutes: 120<br/>unitOfMeasure: CBM"]
            ULD["ULD:<br/>allowedClassFlags: GENERAL, PRIORITY,<br/>TEMPERATURE_CONTROLLED<br/>maxHoldDurationMinutes: 90<br/>unitOfMeasure: KG"]
        end
        AT["allowedTransitions:<br/>  HELD: [COMMITTED, RELEASED]<br/>  COMMITTED: [LOADED, RELEASED]<br/>  LOADED: [RELEASED]"]
        TS["terminalStates:<br/>  - RELEASED"]
    end

    subgraph Loader["CapacityConfigLoader (@PostConstruct)"]
        PC["loadConfig()"]
        subgraph InMemoryCatalog["In-Memory Product Catalog"]
            CatalogMap["capacityTypeMap:<br/>Map&lt;CapacityType, ProductRules&gt;"]
            ClassFlagMap["classFlags per type"]
            HandlingMap["handling rules per type"]
        end
    end

    subgraph PolicyEngine["Policy Engine (Deterministic, Config-Driven)"]
        Q1["isClassFlagAllowed(type, flag)"]
        Q2["isSupportedCapacityType(type)"]
        Q3["isTransitionAllowed(from, to)"]
        Q4["isTerminalState(state)"]
        Q5["getMaxHoldDurationMinutes(type)"]
        Q6["getUnitOfMeasure(type)"]
    end

    ConfigFile -->|"Startup (once)"| PC
    PC --> InMemoryCatalog
    InMemoryCatalog --> PolicyEngine

    subgraph Usage["Service Layer (Every Request)"]
        U1["1. Validate capacity type exists"]
        U2["2. Validate class flag eligibility"]
        U3["3. Check state transition rules"]
        U4["4. Apply handling constraints"]
        U5["5. Calculate hold expiry TTL"]
    end

    PolicyEngine -->|"All checks pass"| DDB["Proceed to DynamoDB"]
    PolicyEngine -->|"Any check fails"| Reject["Reject BEFORE DynamoDB"]

    Usage --> PolicyEngine

    style PolicyEngine fill:#e8f5e9
    style InMemoryCatalog fill:#fff8e1
    style Reject fill:#ffcdd2
    style DDB fill:#e3f2fd
```

### Product Categories (Class Flags)

| Capacity Type | Allowed Class Flags | Unit | Max Hold |
|---------------|---------------------|------|----------|
| FLIGHT | GENERAL, PRIORITY, EXPRESS, DANGEROUS_GOODS | KG | 60 min |
| WAREHOUSE | GENERAL, COLD_STORAGE, HAZMAT, OVERSIZED | CBM | 120 min |
| ULD | GENERAL, PRIORITY, TEMPERATURE_CONTROLLED | KG | 90 min |

### Policy Engine Rules (All In-Memory)

| Rule | Check | Reject If |
|------|-------|-----------|
| Class Flag Eligibility | `classFlag ∈ type.allowedClassFlags` | Flag not allowed for capacity type |
| Capacity Type | `capacityType ∈ capacityTypes` | Unknown capacity type |
| State Transition | `targetState ∈ allowedTransitions[currentState]` | Invalid transition |
| Terminal State | `state ∈ terminalStates` | Attempting transition from terminal |
| Handling Constraints | Unit of measure compatibility | Incompatible unit |

---

## 10. Product Catalog & Policy Engine Flow

This diagram explicitly shows how the in-memory Product Catalog and Policy Engine are consulted **before** any DynamoDB mutation.

> **Non-Negotiable:** Product rules and policy are enforced in memory before any DynamoDB write. DynamoDB is used ONLY for state persistence, quantity mutations, and idempotency.

```mermaid
flowchart TB
    subgraph Startup["Application Startup (Once)"]
        ConfigYML[(capacity-config.yml)]
        Loader["CapacityConfigLoader<br/>@PostConstruct"]
        ConfigYML -->|"Parse YAML"| Loader
    end

    subgraph InMemory["In-Memory Layer (No Network I/O)"]
        subgraph ProductCatalog["Product Catalog Map"]
            direction TB
            CatFlight["FLIGHT →<br/>classFlags: [GENERAL, PRIORITY,<br/>EXPRESS, DANGEROUS_GOODS]<br/>maxHold: 60min, unit: KG"]
            CatWarehouse["WAREHOUSE →<br/>classFlags: [GENERAL, COLD_STORAGE,<br/>HAZMAT, OVERSIZED]<br/>maxHold: 120min, unit: CBM"]
            CatULD["ULD →<br/>classFlags: [GENERAL, PRIORITY,<br/>TEMPERATURE_CONTROLLED]<br/>maxHold: 90min, unit: KG"]
        end

        subgraph TransitionRules["Transition Rules"]
            TR["HELD → [COMMITTED, RELEASED]<br/>COMMITTED → [LOADED, RELEASED]<br/>LOADED → [RELEASED]<br/>RELEASED → (terminal)"]
        end

        subgraph PolicyEngine["Policy Engine (Deterministic)"]
            Check1["① Class Flag Check<br/>Is classFlag allowed<br/>for this capacityType?"]
            Check2["② Capacity Type Check<br/>Is capacityType<br/>supported?"]
            Check3["③ Transition Check<br/>Is state transition<br/>allowed?"]
            Check4["④ Constraint Check<br/>Are handling rules<br/>satisfied?"]
        end

        Loader --> ProductCatalog
        Loader --> TransitionRules
        ProductCatalog --> PolicyEngine
        TransitionRules --> PolicyEngine
    end

    subgraph Request["Every API Request"]
        Req[Incoming Request]
        Service[StockService]
    end

    subgraph Decision["Policy Decision (In-Memory)"]
        Evaluate{Evaluate All<br/>Policy Rules}
        Pass["✓ POLICY PASS<br/>All rules satisfied"]
        Fail["✗ POLICY FAIL<br/>Rule violation detected"]
    end

    subgraph DynamoDB["DynamoDB (Only After Policy Pass)"]
        TxWrite["TransactWriteItems<br/>- State persistence<br/>- Quantity mutations<br/>- Idempotency checks"]
        StockTable[(CapacityStock)]
        ResvTable[(Reservations)]
    end

    subgraph Response["API Response"]
        Success[200 OK<br/>Operation succeeded]
        Rejected[4xx Error<br/>Policy violation]
    end

    Req --> Service
    Service -->|"Consult Policy"| PolicyEngine
    PolicyEngine --> Evaluate
    Evaluate -->|"All checks pass"| Pass
    Evaluate -->|"Any check fails"| Fail

    Pass -->|"Proceed to persistence"| TxWrite
    TxWrite --> StockTable
    TxWrite --> ResvTable
    TxWrite --> Success

    Fail -->|"Reject immediately"| Rejected

    style ProductCatalog fill:#fff8e1
    style PolicyEngine fill:#e8f5e9
    style Pass fill:#c8e6c9
    style Fail fill:#ffcdd2
    style Rejected fill:#ffcdd2
    style DynamoDB fill:#e3f2fd
```

### What Gets Checked Where

| Layer | What It Does | Examples |
|-------|--------------|----------|
| **Product Catalog (In-Memory)** | Stores product rules | Class flags per type, hold durations, units |
| **Policy Engine (In-Memory)** | Enforces business rules | Flag eligibility, transition validity, constraints |
| **DynamoDB** | Persists state, ensures consistency | Atomic writes, capacity math, idempotency |

### Policy Engine Guarantees

1. **No AI/ML** — Purely deterministic, config-driven logic
2. **No Network I/O** — All checks happen in application memory
3. **Fail Fast** — Invalid requests rejected before touching DynamoDB
4. **Single Source of Truth** — All rules derive from `capacity-config.yml`

---

## Quick Reference

### API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/stocks/hold` | POST | Reserve capacity (HELD state) |
| `/stocks/commit` | POST | Confirm reservation (COMMITTED state) |
| `/stocks/load` | POST | Mark as loaded (LOADED state) |
| `/stocks/release` | POST | Release reservation (RELEASED state) |
| `/stocks` | GET | List/query stock items |
| `/stocks/{pk}/{sk}` | GET | Get single stock item |

### Key Patterns

| Entity | Key Pattern | Example |
|--------|-------------|---------|
| Stock PK (FLIGHT) | `FLIGHT#<flightId>#<departureDate>` | `FLIGHT#SQ123#2024-03-15` |
| Stock SK (FLIGHT) | `WINDOW#<departureDatetime>` | `WINDOW#2024-03-15T08:00:00Z` |
| Reservation ID | `RESV#<shipmentId>#<capacityType>#<pk>#<sk>` | `RESV#SHIP001#FLIGHT#...` |

### HTTP Response Codes

| Code | Meaning |
|------|---------|
| 200 | Success (check `isIdempotent` flag) |
| 400 | Validation error |
| 404 | Stock or reservation not found |
| 409 | Insufficient capacity or concurrent modification |
| 422 | Invalid state transition |
