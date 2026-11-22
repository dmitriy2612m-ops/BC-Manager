"""Shared pytest fixtures for the procedural Python core.

The fixtures expose both the raw shared-fixtures JSON files and a normalized
dataset, so that tests can build small synthetic inputs while still validating
the real contract with ``shared-fixtures/``.
"""

from __future__ import annotations

import json
import sys
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any

import pytest

# Make ``src`` importable when pytest is invoked from the project root.
PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from src import data_loader  # noqa: E402

FIXTURES_DIR = PROJECT_ROOT.parent / "shared-fixtures"
DATASET_PATH = FIXTURES_DIR / "test_dataset.json"
EXPECTED_PATH = FIXTURES_DIR / "expected_results.json"


@pytest.fixture(scope="session")
def dataset_path() -> Path:
    """Path to the shared ``test_dataset.json``."""
    return DATASET_PATH


@pytest.fixture(scope="session")
def expected_path() -> Path:
    """Path to the shared ``expected_results.json``."""
    return EXPECTED_PATH


@pytest.fixture(scope="session")
def raw_dataset() -> dict[str, Any]:
    """Raw JSON content of ``test_dataset.json``."""
    with DATASET_PATH.open("r", encoding="utf-8") as handle:
        return json.load(handle)


@pytest.fixture(scope="session")
def expected_results() -> dict[str, Any]:
    """Raw JSON content of ``expected_results.json``."""
    with EXPECTED_PATH.open("r", encoding="utf-8") as handle:
        return json.load(handle)


@pytest.fixture(scope="session")
def dataset() -> dict[str, Any]:
    """Normalized dataset produced by :func:`data_loader.load_dataset`."""
    return data_loader.load_dataset(DATASET_PATH)


@pytest.fixture
def make_premises() -> Any:
    """Factory building a normalized premises dict with sensible defaults."""

    def _make(**overrides: Any) -> dict[str, Any]:
        item: dict[str, Any] = {
            "id": 1,
            "code": "T-001",
            "building": "Корпус Т",
            "floor": 1,
            "type": "OFFICE",
            "area_sqm": Decimal("50.00"),
            "base_rate_per_sqm": Decimal("1000.00"),
            "status": "AVAILABLE",
            "has_furniture": False,
            "has_showcase": False,
            "capacity_people": None,
            "is_covered": False,
            "description": None,
        }
        item.update(overrides)
        return item

    return _make


@pytest.fixture
def make_contract() -> Any:
    """Factory building a normalized contract dict."""

    def _make(**overrides: Any) -> dict[str, Any]:
        item: dict[str, Any] = {
            "id": 1,
            "contract_number": "BC-2026-9001",
            "premises_id": 1,
            "tenant_id": 1,
            "start_date": date(2026, 1, 1),
            "end_date": date(2026, 12, 31),
            "monthly_rent": Decimal("100000.00"),
            "discount_percent": Decimal("0.00"),
            "deposit_amount": Decimal("0.00"),
            "status": "ACTIVE",
            "created_by": 1,
        }
        item.update(overrides)
        return item

    return _make


@pytest.fixture
def make_payment() -> Any:
    """Factory building a normalized payment dict."""

    def _make(**overrides: Any) -> dict[str, Any]:
        item: dict[str, Any] = {
            "id": 1,
            "contract_id": 1,
            "period_number": 1,
            "due_date": date(2025, 12, 1),
            "amount_due": Decimal("100000.00"),
            "paid_amount": Decimal("0.00"),
            "paid_date": None,
            "penalty_amount": Decimal("0.00"),
            "status": "PENDING",
        }
        item.update(overrides)
        return item

    return _make


@pytest.fixture
def make_request() -> Any:
    """Factory building a normalized maintenance request dict."""

    def _make(**overrides: Any) -> dict[str, Any]:
        item: dict[str, Any] = {
            "id": 1,
            "premises_id": 1,
            "tenant_id": None,
            "title": "Тестовая заявка",
            "description": "Описание",
            "category": "GENERAL",
            "priority": "MEDIUM",
            "status": "OPEN",
            "created_at": datetime(2026, 1, 15, 10, 0, 0),
            "resolved_at": None,
        }
        item.update(overrides)
        return item

    return _make
