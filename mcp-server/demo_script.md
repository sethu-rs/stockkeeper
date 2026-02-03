# StockKeeper MCP Demo Script

**Duration:** ~90 seconds
**Prerequisites:** DynamoDB Local running, StockKeeper backend running (`./gradlew bootRun`), MCP server configured in Claude.

---

## Step 1 — Discover available stock

> **You say:** "What stock do we have available?"

Claude calls `get_stocks` and shows all three seeded items (FLIGHT, WAREHOUSE, ULD) with their capacity buckets.

---

## Step 2 — Hold capacity on a flight

> **You say:** "Hold 500 KG of GENERAL capacity on the BA-2173 flight for shipment SHP-001."

Claude calls `hold_stock` with:
- `shipment_id`: `SHP-001`
- `stock_pk`: `FLIGHT#BA-2173#2025-08-15`
- `stock_sk`: `WINDOW#2025-08-15T14:30:00Z`
- `requested_quantity`: `500`
- `class_flag`: `GENERAL`

Response shows `status: HELD`, `idempotent: false`, and the deterministic `reservation_id`.

---

## Step 3 — Demonstrate idempotency

> **You say:** "Hold the same capacity again — same shipment, same flight, same quantity."

Claude calls `hold_stock` with identical arguments. Response shows the **same reservation** with `idempotent: true`. Capacity was NOT decremented a second time.

---

## Step 4 — Check updated stock

> **You say:** "Show me the current stock for that flight."

Claude calls `get_stock_by_key` with the flight's pk/sk. `available_capacity` is down by 500, `held_capacity` is up by 500.

---

## Step 5 — Commit the reservation

> **You say:** "Commit that reservation."

Claude calls `commit_stock` with the `reservation_id` from step 2. Response shows `status: COMMITTED`.

---

## Step 6 — Release the reservation

> **You say:** "Release it."

Claude calls `release_stock` with the same `reservation_id`. Response shows `status: RELEASED`. Capacity returns to `available_capacity`.

---

## Step 7 — Verify final state

> **You say:** "Show me the final stock state."

Claude calls `get_stocks`. All capacity values are back to their original seeded amounts.

---

## Key points to highlight during demo

- **No IDs passed by the user** — Claude discovers keys from query responses.
- **Idempotency is automatic** — retrying a hold is safe, no double-decrement.
- **Deterministic reservation_id** — derived from business fields, not random.
- **Full lifecycle** in natural language: query → hold → commit → release.
