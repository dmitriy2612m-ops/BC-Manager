"""Occupancy and revenue analytics (Algorithm 6).

Procedural implementation. Grouping is done with plain ``dict`` objects and
floors are emitted in ascending order to mirror the Java ``TreeMap``.
"""

from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
from typing import Any

MONEY_QUANTUM = Decimal("0.01")
PERCENT_QUANTUM = Decimal("0.01")

#: Occupying contract statuses for occupancy analytics.
OCCUPYING_STATUSES = ("ACTIVE",)

#: Window (in days) for the expiring-contracts block.
EXPIRING_WINDOW_DAYS = 30

#: Stable display order for premises types (mirrors the domain enum order).
TYPE_ORDER: dict[str, int] = {
    "OFFICE": 0,
    "RETAIL": 1,
    "CONFERENCE": 2,
    "PARKING": 3,
}


def money(value: Decimal | int | float | str) -> Decimal:
    """Quantize a value to kopecks using ``ROUND_HALF_UP``.

    Args:
        value: Any numeric value convertible to ``Decimal``.

    Returns:
        Money amount rounded to 2 digits.
    """
    return Decimal(str(value)).quantize(MONEY_QUANTUM, rounding="ROUND_HALF_UP")


def percent(part: Decimal, total: Decimal) -> Decimal:
    """Compute ``part / total * 100`` with ``HALF_UP`` rounding.

    Args:
        part: Numerator.
        total: Denominator.

    Returns:
        Percentage rounded to 2 digits, or ``0.00`` when ``total`` is zero.
    """
    if total == 0:
        return Decimal("0.00")
    return (part / total * Decimal(100)).quantize(
        PERCENT_QUANTUM, rounding="ROUND_HALF_UP"
    )


def _occupancy_bucket() -> dict[str, Decimal]:
    """Create a fresh occupancy accumulator."""
    return {"total": Decimal("0.00"), "occupied": Decimal("0.00")}


def compute_occupancy_analytics(
    premises_list: list[dict[str, Any]],
    contracts: list[dict[str, Any]],
    payments: list[dict[str, Any]],
    maintenance_requests: list[dict[str, Any]],
    as_of: date,
) -> dict[str, Any]:
    """Compute occupancy, MRR, yield, expiring contracts and open incidents.

    A premises counts as occupied when it has a contract in status ``ACTIVE``
    whose ``[start_date, end_date]`` interval contains ``as_of``.

    Args:
        premises_list: Normalized premises.
        contracts: Normalized contracts.
        payments: Normalized payments (used for the overdue indicator only).
        maintenance_requests: Normalized maintenance requests.
        as_of: Reporting date.

    Returns:
        Dict with ``as_of``, ``total_area_sqm``, ``occupied_area_sqm``,
        ``occupancy_percent``, ``mrr``, ``average_yield_per_sqm``,
        ``by_type``, ``by_floor``, ``expiring_contracts`` and
        ``open_critical_requests``.
    """
    occupied_ids: set[int] = set()
    mrr = Decimal("0.00")
    for contract in contracts:
        if contract["status"] not in OCCUPYING_STATUSES:
            continue
        if contract["start_date"] <= as_of <= contract["end_date"]:
            occupied_ids.add(contract["premises_id"])
            mrr += contract["monthly_rent"]

    total_area = Decimal("0.00")
    occupied_area = Decimal("0.00")
    by_type: dict[str, dict[str, Decimal]] = {}
    by_floor: dict[int, dict[str, Decimal]] = {}

    for premises in premises_list:
        area = premises["area_sqm"]
        is_occupied = premises["id"] in occupied_ids

        total_area += area
        if is_occupied:
            occupied_area += area

        type_bucket = by_type.setdefault(premises["type"], _occupancy_bucket())
        type_bucket["total"] += area
        if is_occupied:
            type_bucket["occupied"] += area

        floor_bucket = by_floor.setdefault(premises["floor"], _occupancy_bucket())
        floor_bucket["total"] += area
        if is_occupied:
            floor_bucket["occupied"] += area

    total_area = money(total_area)
    occupied_area = money(occupied_area)
    mrr = money(mrr)

    average_yield = (
        money(mrr / occupied_area) if occupied_area > 0 else Decimal("0.00")
    )

    type_rows = []
    for type_name in sorted(by_type, key=lambda name: TYPE_ORDER.get(name, 99)):
        bucket = by_type[type_name]
        type_rows.append(
            {
                "type": type_name,
                "total_area_sqm": money(bucket["total"]),
                "occupied_area_sqm": money(bucket["occupied"]),
                "occupancy_percent": percent(bucket["occupied"], bucket["total"]),
            }
        )

    floor_rows = []
    for floor in sorted(by_floor):
        bucket = by_floor[floor]
        floor_rows.append(
            {
                "floor": floor,
                "total_area_sqm": money(bucket["total"]),
                "occupied_area_sqm": money(bucket["occupied"]),
                "occupancy_percent": percent(bucket["occupied"], bucket["total"]),
            }
        )

    horizon = as_of + timedelta(days=EXPIRING_WINDOW_DAYS)
    expiring = []
    for contract in contracts:
        if contract["status"] != "ACTIVE":
            continue
        if as_of <= contract["end_date"] <= horizon:
            expiring.append(
                {
                    "contract_id": contract["id"],
                    "contract_number": contract["contract_number"],
                    "end_date": contract["end_date"],
                    "days_left": (contract["end_date"] - as_of).days,
                }
            )
    expiring.sort(key=lambda row: (row["end_date"], row["contract_id"]))

    open_critical = sum(
        1
        for request in maintenance_requests
        if request["priority"] == "CRITICAL" and request["status"] in ("OPEN", "IN_PROGRESS")
    )

    overdue_total = money(
        sum(
            (
                payment["amount_due"] - payment["paid_amount"]
                for payment in payments
                if payment["status"] == "OVERDUE"
            ),
            Decimal("0.00"),
        )
    )
    overdue_count = sum(1 for payment in payments if payment["status"] == "OVERDUE")

    return {
        "as_of": as_of,
        "total_area_sqm": total_area,
        "occupied_area_sqm": occupied_area,
        "occupancy_percent": percent(occupied_area, total_area),
        "mrr": mrr,
        "average_yield_per_sqm": average_yield,
        "by_type": type_rows,
        "by_floor": floor_rows,
        "expiring_contracts": expiring,
        "open_critical_requests": open_critical,
        "overdue_payments_count": overdue_count,
        "overdue_payments_amount": overdue_total,
    }


def contracts_to_csv_rows(
    contracts: list[dict[str, Any]],
    premises_list: list[dict[str, Any]],
    tenants: list[dict[str, Any]],
) -> list[list[str]]:
    """Flatten contracts with premises/tenant data into CSV rows.

    Args:
        contracts: Normalized contracts.
        premises_list: Normalized premises.
        tenants: Normalized tenants.

    Returns:
        List of string rows (without header), sorted by contract number.
    """
    premises_by_id = {premises["id"]: premises for premises in premises_list}
    tenants_by_id = {tenant["id"]: tenant for tenant in tenants}

    rows: list[list[str]] = []
    for contract in sorted(contracts, key=lambda item: item["contract_number"]):
        premises = premises_by_id.get(contract["premises_id"], {})
        tenant = tenants_by_id.get(contract["tenant_id"], {})
        rows.append(
            [
                contract["contract_number"],
                premises.get("code", ""),
                premises.get("type", ""),
                str(premises.get("floor", "")),
                tenant.get("company_name", ""),
                tenant.get("inn", ""),
                contract["start_date"].isoformat(),
                contract["end_date"].isoformat(),
                str(contract["monthly_rent"]),
                contract["status"],
            ]
        )
    return rows
