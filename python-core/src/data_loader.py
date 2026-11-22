"""Loading and normalization of the shared JSON dataset.

The loader converts raw JSON structures into dictionaries/lists with
``Decimal`` money values and ``date``/``datetime`` objects, so that the
algorithm modules can work with a stable, typed in-memory representation.

All functions are pure: they never mutate their arguments and never rely on
hidden global mutable state.
"""

from __future__ import annotations

import json
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any

MONEY_QUANTUM = Decimal("0.01")
DATE_FORMAT = "%Y-%m-%d"
DATETIME_FORMAT = "%Y-%m-%dT%H:%M:%S"


def _money(value: Any) -> Decimal:
    """Convert a JSON money value into a 2-digit ``Decimal``.

    Args:
        value: Raw value from JSON (``str``, ``int``, ``float`` or ``None``).

    Returns:
        Money amount quantized to kopecks (``HALF_UP``).
    """
    if value is None:
        return Decimal("0.00")
    result = Decimal(str(value))
    return result.quantize(MONEY_QUANTUM, rounding="ROUND_HALF_UP")


def _decimal(value: Any) -> Decimal:
    """Convert a JSON numeric value into a ``Decimal`` without rounding."""
    if value is None:
        return Decimal("0")
    return Decimal(str(value))


def parse_date(value: str | None) -> date | None:
    """Parse an ISO-8601 date string (``YYYY-MM-DD``).

    Args:
        value: Date string or ``None``.

    Returns:
        Parsed ``date`` or ``None`` when the input is empty.
    """
    if not value:
        return None
    return datetime.strptime(value, DATE_FORMAT).date()


def parse_datetime(value: str | None) -> datetime | None:
    """Parse an ISO-8601 datetime string.

    Supports both ``YYYY-MM-DDTHH:MM:SS`` and plain ``YYYY-MM-DD`` values.

    Args:
        value: Datetime string or ``None``.

    Returns:
        Parsed ``datetime`` or ``None`` when the input is empty.
    """
    if not value:
        return None
    try:
        return datetime.strptime(value, DATETIME_FORMAT)
    except ValueError:
        return datetime.strptime(value, DATE_FORMAT)


def normalize_premises(raw: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Normalize the ``premises`` section of the dataset.

    Args:
        raw: Raw list of premises dicts.

    Returns:
        New list of dicts with numeric fields converted to ``Decimal``/``int``.
    """
    result: list[dict[str, Any]] = []
    for item in raw:
        result.append(
            {
                "id": int(item["id"]),
                "code": item["code"],
                "building": item["building"],
                "floor": int(item["floor"]),
                "type": item["type"],
                "area_sqm": _decimal(item["area_sqm"]),
                "base_rate_per_sqm": _decimal(item["base_rate_per_sqm"]),
                "status": item["status"],
                "has_furniture": bool(item.get("has_furniture", False)),
                "has_showcase": bool(item.get("has_showcase", False)),
                "capacity_people": item.get("capacity_people"),
                "is_covered": bool(item.get("is_covered", True)),
                "description": item.get("description"),
            }
        )
    return result


def normalize_tenants(raw: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Normalize the ``tenants`` section of the dataset.

    Args:
        raw: Raw list of tenant dicts.

    Returns:
        New list of dicts with typed fields.
    """
    result: list[dict[str, Any]] = []
    for item in raw:
        result.append(
            {
                "id": int(item["id"]),
                "company_name": item["company_name"],
                "inn": item["inn"],
                "contact_person": item["contact_person"],
                "phone": item["phone"],
                "email": item["email"],
                "is_active": bool(item.get("is_active", True)),
            }
        )
    return result


def normalize_contracts(raw: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Normalize the ``contracts`` section of the dataset.

    Args:
        raw: Raw list of contract dicts.

    Returns:
        New list of dicts with dates parsed and money converted.
    """
    result: list[dict[str, Any]] = []
    for item in raw:
        result.append(
            {
                "id": int(item["id"]),
                "contract_number": item["contract_number"],
                "premises_id": int(item["premises_id"]),
                "tenant_id": int(item["tenant_id"]),
                "start_date": parse_date(item["start_date"]),
                "end_date": parse_date(item["end_date"]),
                "monthly_rent": _money(item["monthly_rent"]),
                "discount_percent": _decimal(item["discount_percent"]),
                "deposit_amount": _money(item["deposit_amount"]),
                "status": item["status"],
                "created_by": item.get("created_by"),
            }
        )
    return result


def normalize_payments(raw: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Normalize the ``payments`` section of the dataset.

    Args:
        raw: Raw list of payment dicts.

    Returns:
        New list of dicts with dates parsed and money converted.
    """
    result: list[dict[str, Any]] = []
    for item in raw:
        result.append(
            {
                "id": int(item["id"]),
                "contract_id": int(item["contract_id"]),
                "period_number": int(item["period_number"]),
                "due_date": parse_date(item["due_date"]),
                "amount_due": _money(item["amount_due"]),
                "paid_amount": _money(item["paid_amount"]),
                "paid_date": parse_date(item.get("paid_date")),
                "penalty_amount": _money(item.get("penalty_amount", "0.00")),
                "status": item["status"],
            }
        )
    return result


def normalize_maintenance_requests(
    raw: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Normalize the ``maintenance_requests`` section of the dataset.

    Args:
        raw: Raw list of maintenance request dicts.

    Returns:
        New list of dicts with timestamps parsed.
    """
    result: list[dict[str, Any]] = []
    for item in raw:
        result.append(
            {
                "id": int(item["id"]),
                "premises_id": int(item["premises_id"]),
                "tenant_id": item.get("tenant_id"),
                "title": item["title"],
                "description": item["description"],
                "category": item["category"],
                "priority": item["priority"],
                "status": item["status"],
                "created_at": parse_datetime(item["created_at"]),
                "resolved_at": parse_datetime(item.get("resolved_at")),
            }
        )
    return result


def load_dataset(path: str | Path) -> dict[str, Any]:
    """Load and normalize a full shared-fixtures dataset file.

    Args:
        path: Path to ``test_dataset.json`` or a compatible file.

    Returns:
        Dict with normalized sections plus a ``metadata`` sub-dict.

    Raises:
        FileNotFoundError: When the file does not exist.
        json.JSONDecodeError: When the file is not valid JSON.
    """
    file_path = Path(path)
    with file_path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)

    meta = data.get("metadata", {})
    return {
        "metadata": {
            "name": meta.get("name"),
            "calculation_date": parse_date(meta.get("calculation_date")),
            "maintenance_now": parse_datetime(meta.get("maintenance_now")),
            "currency": meta.get("currency", "RUB"),
            "vat_rate": _decimal(meta.get("vat_rate", "0.20")),
        },
        "premises": normalize_premises(data.get("premises", [])),
        "tenants": normalize_tenants(data.get("tenants", [])),
        "contracts": normalize_contracts(data.get("contracts", [])),
        "payments": normalize_payments(data.get("payments", [])),
        "maintenance_requests": normalize_maintenance_requests(
            data.get("maintenance_requests", [])
        ),
        "scenarios": data.get("scenarios", {}),
    }
