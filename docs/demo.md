# StockKeeper Demo Guide

## Prerequisites

- Java 21
- Python 3.10.x
- Docker (or Colima) for DynamoDB Local

## 1. Start DynamoDB Local

```bash
docker run -d --name dynamodb-local -p 8000:8000 amazon/dynamodb-local
```

Verify it's running:

```bash
aws dynamodb list-tables --endpoint-url http://localhost:8000 --region us-east-1
```

## 2. Start the Application

```bash
./gradlew bootRun
```

On startup the app will:
- Create `CapacityStock` and `Reservations` tables if they don't exist
- Seed three sample stock items (FLIGHT, WAREHOUSE, ULD) on first run

## 3. Open Swagger UI

```
http://localhost:8080/swagger-ui/index.html
```

OpenAPI JSON is at `http://localhost:8080/v3/api-docs`.

All endpoints have pre-filled example payloads — click **Try it out** and **Execute** directly.

## 4. Suggested Demo Flow

Walk through the full reservation lifecycle using Swagger UI:

### Step 1 — View seeded stock

**GET /stocks** — returns all three seeded items. Note `available_capacity` values.

### Step 2 — Hold capacity

**POST /stock/hold** — use the pre-filled FLIGHT example. Response shows `status: HELD` and `idempotent: false`.

### Step 3 — Verify idempotency

**POST /stock/hold** — send the exact same request again. Response shows the same reservation with `idempotent: true`. Capacity is not decremented twice.

### Step 4 — Inspect stock after hold

**GET /stocks** — `available_capacity` decreased by 500, `held_capacity` increased by 500.

### Step 5 — Commit

**POST /stock/commit** — use the same business fields (no quantity needed). Response shows `status: COMMITTED`.

### Step 6 — Load

**POST /stock/load** — same fields again. Response shows `status: LOADED`.

### Step 7 — Release

**POST /stock/release** — same fields. Response shows `status: RELEASED`. Capacity returns to `available_capacity`.

### Step 8 — Verify final stock state

**GET /stocks** — capacity is back to the original values.

## 5. Error Scenarios to Try

| Action | Expected |
|---|---|
| Hold with `requested_quantity` exceeding `available_capacity` | 409 Conflict |
| Hold with `capacity_type: "TRUCK"` | 400 Bad Request |
| Commit without a prior hold | 404 Not Found |
| Commit a reservation that is already LOADED | 409 Conflict |
| Release an already-released reservation | 200 with `idempotent: true` |

## Sample Payloads

Full request/response JSON for all endpoints is in [`/docs/sample-request.md`](sample-request.md).
