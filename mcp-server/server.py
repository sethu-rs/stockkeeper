"""
StockKeeper MCP Server — thin adapter between Claude tool calls and the
StockKeeper Spring Boot backend.

Runs over stdio using the official MCP Python SDK.

Usage:
    pip install -r requirements.txt
    python server.py                        # default: http://localhost:8080
    STOCKKEEPER_URL=http://host:9090 python server.py
"""

import asyncio
import json
import logging
import os
import urllib.parse
from typing import Any

import httpx
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import TextContent, Tool

# ── Configuration ─────────────────────────────────────────────────────────

BACKEND_BASE_URL = os.environ.get("STOCKKEEPER_URL", "http://localhost:8080")

# Sort-key prefixes — used to locate the pk/sk boundary inside a
# composite reservation_id (RESV#<shipment>#<type>#<pk…>#<sk…>).
_SK_PREFIXES = frozenset({"WINDOW", "SEGMENT", "STATE"})

logger = logging.getLogger("stockkeeper-mcp")

# ── Key-parsing helpers ───────────────────────────────────────────────────


def _split_reservation_id(reservation_id: str) -> tuple[str, str, str, str]:
    """Parse ``RESV#<shipment>#<type>#<pk>#<sk>`` into four components.

    Both *pk* and *sk* contain ``#`` delimiters, so we scan for a known
    SK prefix (WINDOW | SEGMENT | STATE) to find the boundary.

    Returns:
        (shipment_id, capacity_type, stock_pk, stock_sk)
    """
    parts = reservation_id.split("#")
    if len(parts) < 5 or parts[0] != "RESV":
        raise ValueError(f"Invalid reservation_id format: {reservation_id}")

    shipment_id = parts[1]
    capacity_type = parts[2]
    remaining = parts[3:]

    sk_start: int | None = None
    for i, segment in enumerate(remaining):
        if segment in _SK_PREFIXES:
            sk_start = i
            break

    if sk_start is None:
        raise ValueError(
            f"Cannot locate SK prefix in reservation_id: {reservation_id}"
        )

    stock_pk = "#".join(remaining[:sk_start])
    stock_sk = "#".join(remaining[sk_start:])
    return shipment_id, capacity_type, stock_pk, stock_sk


def _pk_fields(capacity_type: str, stock_pk: str) -> dict[str, str]:
    """Extract type-specific fields from a stock partition key."""
    parts = stock_pk.split("#")
    match capacity_type.upper():
        case "FLIGHT":
            # FLIGHT#<flight_id>#<departure_date>
            return {"flight_id": parts[1], "departure_date": parts[2]}
        case "WAREHOUSE":
            # WH#<warehouse_id>#<zone_type>
            return {"warehouse_id": parts[1], "zone_type": parts[2]}
        case "ULD":
            # ULD#<uld_id>
            return {"uld_id": parts[1]}
        case _:
            raise ValueError(f"Unknown capacity_type: {capacity_type}")


def _sk_fields(capacity_type: str, stock_sk: str) -> dict[str, str]:
    """Extract type-specific fields from a stock sort key."""
    parts = stock_sk.split("#")
    match capacity_type.upper():
        case "FLIGHT":
            # WINDOW#<departure_datetime>
            return {"departure_datetime": parts[1]}
        case "WAREHOUSE":
            # SEGMENT#<origin>#<destination>
            return {"origin": parts[1], "destination": parts[2]}
        case "ULD":
            # STATE#<current_location>
            return {"current_location": parts[1]}
        case _:
            raise ValueError(f"Unknown capacity_type: {capacity_type}")


def _body_from_pk_sk(
    capacity_type: str,
    stock_pk: str,
    stock_sk: str,
    shipment_id: str | None = None,
    **extra: Any,
) -> dict[str, Any]:
    """Build a backend JSON request body from composite keys."""
    body: dict[str, Any] = {"capacity_type": capacity_type}
    if shipment_id is not None:
        body["shipment_id"] = shipment_id
    body.update(_pk_fields(capacity_type, stock_pk))
    body.update(_sk_fields(capacity_type, stock_sk))
    body.update(extra)
    return body


def _body_from_reservation_id(reservation_id: str) -> dict[str, Any]:
    """Parse a reservation_id and produce the backend request body."""
    shipment_id, capacity_type, stock_pk, stock_sk = _split_reservation_id(
        reservation_id
    )
    return _body_from_pk_sk(
        capacity_type, stock_pk, stock_sk, shipment_id=shipment_id
    )


def _capacity_type_from_pk(stock_pk: str) -> str:
    """Derive the canonical capacity_type from a pk prefix."""
    prefix = stock_pk.split("#")[0]
    mapping = {"FLIGHT": "FLIGHT", "WH": "WAREHOUSE", "ULD": "ULD"}
    ct = mapping.get(prefix)
    if ct is None:
        raise ValueError(f"Cannot determine capacity_type from pk prefix '{prefix}'")
    return ct


def _encode_path(value: str) -> str:
    """URL-encode a value for use in a URL path segment (# → %23, etc.)."""
    return urllib.parse.quote(value, safe="")


# ── MCP Server ────────────────────────────────────────────────────────────

server = Server("stockkeeper")


@server.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="get_stocks",
            description=(
                "List all capacity stock entries. "
                "Optionally filter by capacity_type (FLIGHT, WAREHOUSE, or ULD). "
                "Returns pk, sk, capacity buckets (available, held, committed, loaded), "
                "and metadata for each stock item."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "capacity_type": {
                        "type": "string",
                        "enum": ["FLIGHT", "WAREHOUSE", "ULD"],
                        "description": "Filter by capacity type. Omit to list all.",
                    }
                },
                "required": [],
            },
        ),
        Tool(
            name="get_stock_by_key",
            description=(
                "Get a single capacity stock item by its composite key (pk + sk). "
                "Use the exact pk and sk values returned by get_stocks."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "pk": {
                        "type": "string",
                        "description": (
                            "Partition key, e.g. 'FLIGHT#BA-2173#2025-08-15'"
                        ),
                    },
                    "sk": {
                        "type": "string",
                        "description": (
                            "Sort key, e.g. 'WINDOW#2025-08-15T14:30:00Z'"
                        ),
                    },
                },
                "required": ["pk", "sk"],
            },
        ),
        Tool(
            name="hold_stock",
            description=(
                "Hold capacity on a stock item for a shipment. "
                "Uses the stock_pk and stock_sk from the get_stocks response — "
                "do NOT fabricate these keys. "
                "Returns a reservation with a deterministic reservation_id; "
                "use that ID for subsequent commit_stock or release_stock calls. "
                "Naturally idempotent: repeating the exact same hold returns the "
                "existing reservation with idempotent=true and does NOT double-decrement."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "shipment_id": {
                        "type": "string",
                        "description": "Unique shipment identifier, e.g. 'SHP-001'",
                    },
                    "stock_pk": {
                        "type": "string",
                        "description": (
                            "Stock partition key from get_stocks, "
                            "e.g. 'FLIGHT#BA-2173#2025-08-15'"
                        ),
                    },
                    "stock_sk": {
                        "type": "string",
                        "description": (
                            "Stock sort key from get_stocks, "
                            "e.g. 'WINDOW#2025-08-15T14:30:00Z'"
                        ),
                    },
                    "requested_quantity": {
                        "type": "integer",
                        "description": (
                            "Amount of capacity to hold "
                            "(in the stock item's unit_of_measure)"
                        ),
                    },
                    "class_flag": {
                        "type": "string",
                        "description": (
                            "Capacity class — must be one of the stock item's "
                            "class_flags, e.g. 'GENERAL', 'PRIORITY', 'EXPRESS'"
                        ),
                    },
                    "priority_level": {
                        "type": "integer",
                        "description": "Priority level (optional)",
                    },
                },
                "required": [
                    "shipment_id",
                    "stock_pk",
                    "stock_sk",
                    "requested_quantity",
                    "class_flag",
                ],
            },
        ),
        Tool(
            name="commit_stock",
            description=(
                "Commit a held reservation (HELD -> COMMITTED). "
                "Pass the reservation_id returned by hold_stock. "
                "Naturally idempotent: repeating returns idempotent=true."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "reservation_id": {
                        "type": "string",
                        "description": "The reservation_id returned by hold_stock",
                    }
                },
                "required": ["reservation_id"],
            },
        ),
        Tool(
            name="load_stock",
            description=(
                "Load a committed reservation (COMMITTED -> LOADED). "
                "Represents physical loading of goods. "
                "Pass the reservation_id returned by hold_stock. "
                "Naturally idempotent: repeating returns idempotent=true."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "reservation_id": {
                        "type": "string",
                        "description": "The reservation_id returned by hold_stock",
                    }
                },
                "required": ["reservation_id"],
            },
        ),
        Tool(
            name="release_stock",
            description=(
                "Release a reservation (HELD/COMMITTED/LOADED -> RELEASED). "
                "Returns capacity back to available_capacity. "
                "Pass the reservation_id returned by hold_stock. "
                "RELEASED is a terminal state — no further transitions. "
                "Naturally idempotent: repeating returns idempotent=true."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "reservation_id": {
                        "type": "string",
                        "description": "The reservation_id returned by hold_stock",
                    }
                },
                "required": ["reservation_id"],
            },
        ),
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
    async with httpx.AsyncClient(
        base_url=BACKEND_BASE_URL, timeout=10.0
    ) as client:
        try:
            resp: httpx.Response

            match name:
                case "get_stocks":
                    params: dict[str, str] = {}
                    if ct := arguments.get("capacity_type"):
                        params["capacity_type"] = ct
                    resp = await client.get("/stocks", params=params)

                case "get_stock_by_key":
                    pk = _encode_path(arguments["pk"])
                    sk = _encode_path(arguments["sk"])
                    resp = await client.get(f"/stocks/{pk}/{sk}")

                case "hold_stock":
                    stock_pk = arguments["stock_pk"]
                    stock_sk = arguments["stock_sk"]
                    capacity_type = _capacity_type_from_pk(stock_pk)

                    body = _body_from_pk_sk(
                        capacity_type,
                        stock_pk,
                        stock_sk,
                        shipment_id=arguments["shipment_id"],
                        requested_quantity=arguments["requested_quantity"],
                        class_flag=arguments["class_flag"],
                    )
                    if (pl := arguments.get("priority_level")) is not None:
                        body["priority_level"] = pl

                    resp = await client.post("/stock/hold", json=body)

                case "commit_stock":
                    body = _body_from_reservation_id(arguments["reservation_id"])
                    resp = await client.post("/stock/commit", json=body)

                case "load_stock":
                    body = _body_from_reservation_id(arguments["reservation_id"])
                    resp = await client.post("/stock/load", json=body)

                case "release_stock":
                    body = _body_from_reservation_id(arguments["reservation_id"])
                    resp = await client.post("/stock/release", json=body)

                case _:
                    return [
                        TextContent(type="text", text=f"Unknown tool: {name}")
                    ]

            # Format response
            status = resp.status_code
            try:
                data = resp.json()
                body_text = json.dumps(data, indent=2)
            except Exception:
                body_text = resp.text

            return [TextContent(type="text", text=f"HTTP {status}\n{body_text}")]

        except httpx.ConnectError:
            return [
                TextContent(
                    type="text",
                    text=(
                        f"Error: Cannot connect to StockKeeper backend at "
                        f"{BACKEND_BASE_URL}. Is the application running?"
                    ),
                )
            ]
        except ValueError as e:
            return [TextContent(type="text", text=f"Error: {e}")]
        except Exception as e:
            logger.exception("Unexpected error in tool %s", name)
            return [
                TextContent(type="text", text=f"Error: {type(e).__name__}: {e}")
            ]


# ── Entry point ───────────────────────────────────────────────────────────


async def main() -> None:
    async with stdio_server() as (read_stream, write_stream):
        await server.run(
            read_stream,
            write_stream,
            server.create_initialization_options(),
        )


if __name__ == "__main__":
    asyncio.run(main())
