You are a principal-level backend engineer implementing a Spring Boot 3.x application (Java 21, Gradle).

I have written a detailed design specification that must be treated as the single source of truth for this implementation.

IMPORTANT RULES

Do NOT reinterpret or simplify the design

Do NOT introduce alternative idempotency approaches

Do NOT add client-provided idempotency keys

Do NOT use random UUIDs where deterministic keys are specified

The code must directly reflect the design document

1. Design source

Assume the following file exists in the project:

/docs/design.md


This file contains:

DynamoDB table designs

Idempotency strategy

Reservation lifecycle rules

Operation semantics (HOLD / COMMIT / LOAD / RELEASE)

You must implement the system exactly as described in that document.

2. Technology constraints (MANDATORY)

Java 21

Spring Boot 3.x

Gradle (Groovy DSL)

Gradle Wrapper (./gradlew)

AWS SDK v2 for DynamoDB

DynamoDB Local (running in Docker)

No Spring Data DynamoDB

Use low-level DynamoDB client and TransactWriteItems

Lombok allowed

3. Application goals

Build a capacity reservation / stock management service that:

Exposes REST APIs

Uses DynamoDB Local via configurable endpoint

Enforces natural idempotency using deterministic keys

Loads capacity and product configuration from a local YAML config file

Performs validation using in-memory config before DynamoDB access

4. DynamoDB tables (must implement exactly)
4.1 CapacityStock (unified capacity ledger)

Table name: CapacityStock

Partition key: pk (String)

Sort key: sk (String)

Billing: PAY_PER_REQUEST

Key patterns

pk

FLIGHT#<flight_id>#<dep_date>

WH#<warehouse_id>#<zone_type>

ULD#<uld_id>

sk

WINDOW#<departure_datetime>

SEGMENT#<origin>#<destination>

STATE#<current_location>

Attributes

capacity_type (FLIGHT | WAREHOUSE | ULD)

total_capacity

available_capacity

held_capacity

committed_capacity

loaded_capacity

unit_of_measure

class_flags

priority_level

expiry_time

4.2 Reservations (natural idempotency table)

Table name: Reservations

Partition key: reservation_id (String)

TTL attribute: expiry_ts

reservation_id derivation (MANDATORY)

RESV#<shipment_id>#<capacity_type>#<stock_pk>#<stock_sk>


This key is deterministic and MUST be derived in code (never random, never client-provided).

5. Idempotency strategy (CRITICAL)

Idempotency is a business property, not a client responsibility.

No idempotency headers

No separate idempotency table

The same logical operation MUST always map to the same reservation_id

Use DynamoDB conditional writes and transactions to enforce apply-once semantics.

6. Operations to implement

Implement the following operations as REST endpoints:

POST /stock/hold

POST /stock/commit

POST /stock/load

POST /stock/release

GET /stocks

GET /stocks/{pk}/{sk}

7. HOLD_STOCK flow (must match exactly)

Derive reservation_id from request fields

Use TransactWriteItems with:

Update CapacityStock

available_capacity >= :qty
SET available_capacity = available_capacity - :qty,
    held_capacity = held_capacity + :qty


Put Reservations

ConditionExpression: attribute_not_exists(reservation_id)
status = HELD


If transaction fails because reservation already exists:

Treat as idempotent success

Return existing reservation

8. COMMIT / LOAD / RELEASE flows

Read reservation by reservation_id

If already in target or terminal state → return success (idempotent)

Else:

Use TransactWriteItems

Conditional update on Reservations.status

Conditional capacity movement in CapacityStock

9. Configuration-driven validation (IMPORTANT)

Create a local YAML config file (e.g. capacity-config.yml) that defines:

Allowed capacity types

Allowed class flags per capacity type

Max hold duration

Allowed transitions (HOLD → COMMIT → LOAD → RELEASE)

This config must:

Be loaded at startup

Stored in memory

Used to validate API requests before hitting DynamoDB

Claude must generate:

The config file

The config binding class

Validation logic in service layer

10. What to generate (MANDATORY)

Generate:

Project structure

application.yml configured for DynamoDB Local

DynamoDB client configuration

REST controllers

Service layer with idempotency logic

Repository layer using low-level DynamoDB SDK

Config file + loader

Example request/response JSON

Clear comments explaining idempotency decisions

11. Style expectations

Clean, readable code

Explicit error handling

No magic

Interview-grade explanations in comments

Assume this will be demonstrated locally using Postman and DynamoDB Local.

Do not simplify the idempotency model.
Do not introduce client-side idempotency keys.
Do not use random UUIDs for reservations.

Start by generating the project structure and configuration, then the core service logic, then the controllers.

Start by generating the project structure and Gradle build, then proceed to implementation.