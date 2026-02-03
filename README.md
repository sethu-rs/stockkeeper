# StockKeeper Service

Capacity reservation and stock management service.

This repository is intended as a **demo and discussion artifact**.

## Demo
 ### Start here: [AWS_Architecture_and_Flow_Diagrams](docs/api-flow-diagrams.md)
 
 ### Demo : [Demo Guide](docs/demo.md)

The demo guide walks through:
- Local setup (DynamoDB Local)
- Running the service
- Swagger-based API walkthrough
- Idempotent command flows

## Overview
- Command-style, idempotent APIs (HOLD / COMMIT / LOAD / RELEASE)
- DynamoDB-based capacity ledger
- Deterministic, business-key-driven idempotency
- Swagger / OpenAPI for discovery

## Local Run
```bash
./gradlew bootRun
