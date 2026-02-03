# StockKeeper Service

StockKeeper is a stateless, idempotent capacity reservation service designed for high-throughput logistics workflows.
It enforces deterministic state transitions and business rules in memory, with DynamoDB used exclusively for state persistence and idempotency.
The system is deployed in an active–passive multi-region AWS architecture.

This repository is intended as a **demo and discussion artifact**, focused on domain modeling, correctness, and architectural trade-offs.

## Architecture Overview & Flow Diagrams
 ### 👉: [AWS_Architecture_and_Flow_Diagrams](docs/api-flow-diagrams.md)

This document covers:
- Deployment topology and DR assumptions
- In-memory product catalog and policy enforcement
- API flows and state transitions
- Idempotency and conditional mutation patterns

## Demo
 ### 👉: [Demo Guide](docs/demo.md)

The demo guide walks through:
- Local setup (DynamoDB Local)
- Running the service
- Swagger-based API walkthrough
- Idempotent command flows

## Key Characteristics
- Command-style, idempotent APIs (HOLD / COMMIT / LOAD / RELEASE)
- DynamoDB-based capacity ledger
- Deterministic, business-key-driven idempotency
- Swagger / OpenAPI for discovery and exploration

