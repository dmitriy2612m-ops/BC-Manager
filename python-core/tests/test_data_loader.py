"""Tests for ``src.data_loader``: parsing, normalization and validation."""

from __future__ import annotations

import json
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path

import pytest
from src import data_loader
from src.data_loader import (
    load_dataset,
    normalize_contracts,
    normalize_maintenance_requests,
    normalize_payments,
    normalize_premises,
    normalize_tenants,
    parse_date,
    parse_datetime,
)


def test_parse_date_iso() -> None:
    assert parse_date("2026-01-15") == date(2026, 1, 15)


def test_parse_date_empty_returns_none() -> None:
    assert parse_date(None) is None
    assert parse_date("") is None


def test_parse_datetime_full_and_date_only() -> None:
    assert parse_datetime("2026-01-15T12:30:45") == datetime(2026, 1, 15, 12, 30, 45)
    assert parse_datetime("2026-01-15") == datetime(2026, 1, 15, 0, 0, 0)


def test_money_rounds_half_up() -> None:
    assert data_loader._money("100.005") == Decimal("100.01")
    assert data_loader._money("100.004") == Decimal("100.00")
    assert data_loader._money(None) == Decimal("0.00")


def test_normalize_premises_types() -> None:
    raw = [
        {
            "id": "7",
            "code": "X-1",
            "building": "Корпус X",
            "floor": "-1",
            "type": "PARKING",
            "area_sqm": "12.5",
            "base_rate_per_sqm": "900",
            "status": "AVAILABLE",
            "has_furniture": False,
            "has_showcase": False,
            "capacity_people": None,
            "is_covered": False,
            "description": None,
        }
    ]
    result = normalize_premises(raw)
    assert result[0]["id"] == 7
    assert result[0]["floor"] == -1
    assert result[0]["area_sqm"] == Decimal("12.5")
    assert isinstance(result[0]["area_sqm"], Decimal)
    assert result[0]["is_covered"] is False


def test_normalize_premises_defaults_flags() -> None:
    raw = [
        {
            "id": 1,
            "code": "X-1",
            "building": "B",
            "floor": 1,
            "type": "OFFICE",
            "area_sqm": "10",
            "base_rate_per_sqm": "100",
            "status": "AVAILABLE",
        }
    ]
    result = normalize_premises(raw)
    assert result[0]["has_furniture"] is False
    assert result[0]["has_showcase"] is False
    # ``is_covered`` defaults to True in the loader.
    assert result[0]["is_covered"] is True
    assert result[0]["capacity_people"] is None


def test_normalize_tenants_defaults_active() -> None:
    result = normalize_tenants(
        [
            {
                "id": 3,
                "company_name": "Тест",
                "inn": "7701234567",
                "contact_person": "Иванов",
                "phone": "1",
                "email": "a@b.ru",
            }
        ]
    )
    assert result[0]["is_active"] is True
    assert result[0]["id"] == 3


def test_normalize_contracts_dates_and_money() -> None:
    result = normalize_contracts(
        [
            {
                "id": 1,
                "contract_number": "C",
                "premises_id": 1,
                "tenant_id": 1,
                "start_date": "2026-01-01",
                "end_date": "2026-12-31",
                "monthly_rent": "100000",
                "discount_percent": "5",
                "deposit_amount": "0",
                "status": "ACTIVE",
                "created_by": 1,
            }
        ]
    )
    row = result[0]
    assert row["start_date"] == date(2026, 1, 1)
    assert row["end_date"] == date(2026, 12, 31)
    assert row["monthly_rent"] == Decimal("100000.00")
    assert row["discount_percent"] == Decimal("5")


def test_normalize_payments_penalty_default() -> None:
    raw = [
        {
            "id": 1,
            "contract_id": 1,
            "period_number": 1,
            "due_date": "2026-01-01",
            "amount_due": "100",
            "paid_amount": "0",
            "paid_date": None,
            "status": "PENDING",
        }
    ]
    result = normalize_payments(raw)
    assert result[0]["penalty_amount"] == Decimal("0.00")
    assert result[0]["paid_date"] is None


def test_normalize_maintenance_requests() -> None:
    raw = [
        {
            "id": 1,
            "premises_id": 2,
            "title": "T",
            "description": "D",
            "category": "GENERAL",
            "priority": "LOW",
            "status": "OPEN",
            "created_at": "2026-01-15T10:00:00",
            "resolved_at": None,
        }
    ]
    result = normalize_maintenance_requests(raw)
    assert result[0]["tenant_id"] is None
    assert result[0]["created_at"] == datetime(2026, 1, 15, 10, 0, 0)
    assert result[0]["resolved_at"] is None


def test_load_dataset_metadata(dataset_path: Path) -> None:
    dataset = load_dataset(dataset_path)
    assert dataset["metadata"]["calculation_date"] == date(2026, 1, 15)
    assert dataset["metadata"]["maintenance_now"] == datetime(2026, 1, 15, 12, 0, 0)
    assert dataset["metadata"]["vat_rate"] == Decimal("0.20")
    assert dataset["metadata"]["currency"] == "RUB"
    assert len(dataset["premises"]) == 15
    assert len(dataset["contracts"]) == 8
    assert len(dataset["payments"]) == 10
    assert len(dataset["maintenance_requests"]) == 8
    assert len(dataset["tenants"]) == 6


def test_load_dataset_missing_file(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError):
        load_dataset(tmp_path / "nope.json")


def test_load_dataset_invalid_json(tmp_path: Path) -> None:
    bad = tmp_path / "bad.json"
    bad.write_text("{not json", encoding="utf-8")
    with pytest.raises(json.JSONDecodeError):
        load_dataset(bad)


def test_load_dataset_defaults_for_optional_sections(tmp_path: Path) -> None:
    minimal = tmp_path / "min.json"
    minimal.write_text("{}", encoding="utf-8")
    dataset = load_dataset(minimal)
    assert dataset["premises"] == []
    assert dataset["contracts"] == []
    assert dataset["scenarios"] == {}
    assert dataset["metadata"]["vat_rate"] == Decimal("0.20")
