# Stock API – Example JSON Requests & Responses

## 1. HOLD

### Request

**POST** `/stock/hold`

```json
{
  "shipment_id": "SHP-001",
  "capacity_type": "FLIGHT",
  "requested_quantity": 500,
  "class_flag": "GENERAL",
  "priority_level": 1,
  "flight_id": "BA-2173",
  "departure_date": "2025-08-15",
  "departure_datetime": "2025-08-15T14:30:00Z"
}
```

### Response (new hold)

**200 OK**

```json
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
}
```

### Response (idempotent retry)

**200 OK**

```json
{
  "reservation": {
    "...": "same reservation data"
  },
  "idempotent": true
}
```

---

## 2. COMMIT

### Request

**POST** `/stock/commit`

```json
{
  "shipment_id": "SHP-001",
  "capacity_type": "FLIGHT",
  "flight_id": "BA-2173",
  "departure_date": "2025-08-15",
  "departure_datetime": "2025-08-15T14:30:00Z"
}
```

### Response

**200 OK**

```json
{
  "reservation": {
    "reservation_id": "RESV#SHP-001#FLIGHT#FLIGHT#BA-2173#2025-08-15#WINDOW#2025-08-15T14:30:00Z",
    "status": "COMMITTED",
    "...": "other fields"
  },
  "idempotent": false
}
```

---

## 3. LOAD

### Request

**POST** `/stock/load`

```json
{
  "shipment_id": "SHP-001",
  "capacity_type": "FLIGHT",
  "flight_id": "BA-2173",
  "departure_date": "2025-08-15",
  "departure_datetime": "2025-08-15T14:30:00Z"
}
```

### Response

**200 OK**

```json
{
  "reservation": {
    "status": "LOADED",
    "...": "other fields"
  },
  "idempotent": false
}
```

---

## 4. RELEASE

### Request

**POST** `/stock/release`

```json
{
  "shipment_id": "SHP-001",
  "capacity_type": "FLIGHT",
  "flight_id": "BA-2173",
  "departure_date": "2025-08-15",
  "departure_datetime": "2025-08-15T14:30:00Z"
}
```

### Response

**200 OK**

```json
{
  "reservation": {
    "status": "RELEASED",
    "...": "other fields"
  },
  "idempotent": false
}
```

---

## 5. GET /stocks

### Request

**GET** `/stocks`  
**GET** `/stocks?capacity_type=FLIGHT`

### Response

**200 OK**

```json
[
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
  }
]
```

---

## 6. GET /stocks/{pk}/{sk}

### Request

```
GET /stocks/FLIGHT%23BA-2173%232025-08-15/WINDOW%232025-08-15T14%3A30%3A00Z
```

### Response (200 OK)

```json
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
}
```

### Response (404 Not Found)

```json
{
  "timestamp": "2025-08-15T14:30:00Z",
  "status": 404,
  "error": "Not Found",
  "messages": [
    "Stock not found: pk=FLIGHT#BA-2173#2025-08-15, sk=WINDOW#2025-08-15T14:30:00Z"
  ],
  "path": "/stocks/FLIGHT%23BA-2173%232025-08-15/WINDOW%232025-08-15T14%3A30%3A00Z"
}
```
