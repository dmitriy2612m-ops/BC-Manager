"""Tests for ``src.analytics`` (Algorithm 6)."""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any

from src import analytics
from src.analytics import compute_occupancy_analytics, contracts_to_csv_rows, percent

AS_OF = date(2026, 1, 15)


def _analytics(
    premises: list[dict[str, Any]],
    contracts: list[dict[str, Any]],
    payments: list[dict[str, Any]] | None = None,
    requests: list[dict[str, Any]] | None = None,
    as_of: date = AS_OF,
) -> dict[str, Any]:
    return compute_occupancy_analytics(
        premises, contracts, payments or [], requests or [], as_of
    )


# --------------------------------------------------------------------------- #
# Percent helper
# --------------------------------------------------------------------------- #
def test_percent_basic() -> None:
    assert percent(Decimal("50"), Decimal("200")) == Decimal("25.00")


def test_percent_zero_total() -> None:
    assert percent(Decimal("10"), Decimal("0")) == Decimal("0.00")


def test_percent_rounds_half_up() -> None:
    assert percent(Decimal("1"), Decimal("3")) == Decimal("33.33")


# --------------------------------------------------------------------------- #
# Empty business center
# --------------------------------------------------------------------------- #
def test_empty_business_center() -> None:
    result = _analytics([], [])
    assert result["total_area_sqm"] == Decimal("0.00")
    assert result["occupied_area_sqm"] == Decimal("0.00")
    assert result["occupancy_percent"] == Decimal("0.00")
    assert result["mrr"] == Decimal("0.00")
    assert result["average_yield_per_sqm"] == Decimal("0.00")
    assert result["by_type"] == []
    assert result["by_floor"] == []
    assert result["expiring_contracts"] == []
    assert result["open_critical_requests"] == 0


def test_premises_without_contracts() -> None:
    premises = [_make_premises(1, "OFFICE", "50.00", 1)]
    result = _analytics(premises, [])
    assert result["total_area_sqm"] == Decimal("50.00")
    assert result["occupied_area_sqm"] == Decimal("0.00")
    assert result["occupancy_percent"] == Decimal("0.00")
    assert result["by_type"][0]["occupancy_percent"] == Decimal("0.00")


# --------------------------------------------------------------------------- #
# Occupancy
# --------------------------------------------------------------------------- #
def _make_premises(
    pid: int,
    ptype: str = "OFFICE",
    area: str = "50.00",
    floor: int = 1,
) -> dict[str, Any]:
    return {
        "id": pid,
        "code": f"T-{pid}",
        "building": "Корпус Т",
        "floor": floor,
        "type": ptype,
        "area_sqm": Decimal(area),
        "base_rate_per_sqm": Decimal("1000.00"),
        "status": "AVAILABLE",
        "has_furniture": False,
        "has_showcase": False,
        "capacity_people": None,
        "is_covered": False,
        "description": None,
    }


def _make_contract(
    cid: int,
    premises_id: int,
    start: date,
    end: date,
    rent: str = "100000.00",
    status: str = "ACTIVE",
) -> dict[str, Any]:
    return {
        "id": cid,
        "contract_number": f"BC-{cid:04d}",
        "premises_id": premises_id,
        "tenant_id": 1,
        "start_date": start,
        "end_date": end,
        "monthly_rent": Decimal(rent),
        "discount_percent": Decimal("0.00"),
        "deposit_amount": Decimal("0.00"),
        "status": status,
        "created_by": 1,
    }


def test_occupied_area_and_percent() -> None:
    premises = [
        _make_premises(1, "OFFICE", "100.00"),
        _make_premises(2, "OFFICE", "100.00"),
    ]
    contracts = [
        _make_contract(1, 1, date(2025, 1, 1), date(2026, 12, 31)),
    ]
    result = _analytics(premises, contracts)
    assert result["total_area_sqm"] == Decimal("200.00")
    assert result["occupied_area_sqm"] == Decimal("100.00")
    assert result["occupancy_percent"] == Decimal("50.00")


def test_contract_not_covering_as_of_does_not_occupy() -> None:
    premises = [_make_premises(1)]
    contracts = [_make_contract(1, 1, date(2025, 1, 1), date(2025, 12, 31))]
    result = _analytics(premises, contracts)
    assert result["occupied_area_sqm"] == Decimal("0.00")


def test_contract_starting_after_as_of_not_occupied() -> None:
    premises = [_make_premises(1)]
    contracts = [_make_contract(1, 1, date(2026, 3, 1), date(2027, 2, 28))]
    result = _analytics(premises, contracts)
    assert result["occupied_area_sqm"] == Decimal("0.00")


def test_boundary_start_equals_as_of() -> None:
    premises = [_make_premises(1)]
    contracts = [_make_contract(1, 1, AS_OF, date(2026, 12, 31))]
    result = _analytics(premises, contracts)
    assert result["occupied_area_sqm"] == Decimal("50.00")


def test_boundary_end_equals_as_of() -> None:
    premises = [_make_premises(1)]
    contracts = [_make_contract(1, 1, date(2025, 1, 1), AS_OF)]
    result = _analytics(premises, contracts)
    assert result["occupied_area_sqm"] == Decimal("50.00")


def test_non_active_statuses_do_not_occupy() -> None:
    premises = [_make_premises(1)]
    for status in ("DRAFT", "COMPLETED", "TERMINATED"):
        contracts = [_make_contract(1, 1, date(2025, 1, 1), date(2026, 12, 31), status=status)]
        result = _analytics(premises, contracts)
        assert result["occupied_area_sqm"] == Decimal("0.00"), status
        assert result["mrr"] == Decimal("0.00"), status


def test_area_not_double_counted_with_two_contracts() -> None:
    premises = [_make_premises(1, "OFFICE", "100.00")]
    contracts = [
        _make_contract(1, 1, date(2025, 1, 1), date(2026, 12, 31), rent="100000.00"),
        _make_contract(2, 1, date(2025, 6, 1), date(2026, 6, 30), rent="50000.00", status="DRAFT"),
    ]
    result = _analytics(premises, contracts)
    assert result["occupied_area_sqm"] == Decimal("100.00")
    assert result["occupied_area_sqm"] <= result["total_area_sqm"]


# --------------------------------------------------------------------------- #
# MRR and yield
# --------------------------------------------------------------------------- #
def test_mrr_sums_active_contracts() -> None:
    premises = [_make_premises(1), _make_premises(2), _make_premises(3)]
    contracts = [
        _make_contract(1, 1, date(2025, 1, 1), date(2026, 12, 31), rent="120000.00"),
        _make_contract(2, 2, date(2025, 1, 1), date(2026, 12, 31), rent="80000.00"),
        _make_contract(
            3, 3, date(2025, 1, 1), date(2026, 12, 31), rent="99999.00", status="COMPLETED"
        ),
    ]
    result = _analytics(premises, contracts)
    assert result["mrr"] == Decimal("200000.00")


def test_average_yield_per_sqm() -> None:
    premises = [_make_premises(1, area="50.00"), _make_premises(2, area="50.00")]
    contracts = [_make_contract(1, 1, date(2025, 1, 1), date(2026, 12, 31), rent="100000.00")]
    result = _analytics(premises, contracts)
    assert result["average_yield_per_sqm"] == Decimal("2000.00")


def test_average_yield_zero_when_nothing_occupied() -> None:
    premises = [_make_premises(1)]
    result = _analytics(premises, [])
    assert result["average_yield_per_sqm"] == Decimal("0.00")


# --------------------------------------------------------------------------- #
# Breakdowns
# --------------------------------------------------------------------------- #
def test_by_type_order_is_domain_order() -> None:
    premises = [
        _make_premises(1, "PARKING", "10.00", -1),
        _make_premises(2, "RETAIL", "20.00", 1),
        _make_premises(3, "CONFERENCE", "30.00", 3),
        _make_premises(4, "OFFICE", "40.00", 2),
    ]
    result = _analytics(premises, [])
    assert [row["type"] for row in result["by_type"]] == [
        "OFFICE",
        "RETAIL",
        "CONFERENCE",
        "PARKING",
    ]


def test_by_type_aggregates_area() -> None:
    premises = [
        _make_premises(1, "OFFICE", "40.00", 1),
        _make_premises(2, "OFFICE", "60.00", 2),
    ]
    contracts = [_make_contract(1, 1, date(2025, 1, 1), date(2026, 12, 31))]
    result = _analytics(premises, contracts)
    office = result["by_type"][0]
    assert office["total_area_sqm"] == Decimal("100.00")
    assert office["occupied_area_sqm"] == Decimal("40.00")
    assert office["occupancy_percent"] == Decimal("40.00")


def test_by_floor_sorted_ascending_including_basement() -> None:
    premises = [
        _make_premises(1, floor=3),
        _make_premises(2, floor=-1),
        _make_premises(3, floor=1),
    ]
    result = _analytics(premises, [])
    assert [row["floor"] for row in result["by_floor"]] == [-1, 1, 3]


def test_by_floor_occupancy() -> None:
    premises = [
        _make_premises(1, floor=2, area="100.00"),
        _make_premises(2, floor=2, area="100.00"),
    ]
    contracts = [_make_contract(1, 1, date(2025, 1, 1), date(2026, 12, 31))]
    result = _analytics(premises, contracts)
    row = result["by_floor"][0]
    assert row["floor"] == 2
    assert row["occupancy_percent"] == Decimal("50.00")


# --------------------------------------------------------------------------- #
# Expiring contracts
# --------------------------------------------------------------------------- #
def test_expiring_within_30_days() -> None:
    premises = [_make_premises(1)]
    contracts = [
        _make_contract(1, 1, date(2025, 1, 1), date(2026, 2, 10)),
    ]
    result = _analytics(premises, contracts)
    assert len(result["expiring_contracts"]) == 1
    assert result["expiring_contracts"][0]["days_left"] == 26


def test_expiring_boundary_exactly_30_days() -> None:
    premises = [_make_premises(1)]
    contracts = [_make_contract(1, 1, date(2025, 1, 1), date(2026, 2, 14))]
    result = _analytics(premises, contracts)
    assert len(result["expiring_contracts"]) == 1
    assert result["expiring_contracts"][0]["days_left"] == 30


def test_expiring_beyond_30_days_excluded() -> None:
    premises = [_make_premises(1)]
    contracts = [_make_contract(1, 1, date(2025, 1, 1), date(2026, 2, 15))]
    result = _analytics(premises, contracts)
    assert result["expiring_contracts"] == []


def test_expiring_ignores_non_active() -> None:
    premises = [_make_premises(1)]
    contracts = [
        _make_contract(1, 1, date(2025, 1, 1), date(2026, 2, 1), status="DRAFT"),
    ]
    result = _analytics(premises, contracts)
    assert result["expiring_contracts"] == []


def test_expiring_sorted_by_end_date_then_id() -> None:
    premises = [_make_premises(1), _make_premises(2)]
    contracts = [
        _make_contract(2, 2, date(2025, 1, 1), date(2026, 2, 10)),
        _make_contract(1, 1, date(2025, 1, 1), date(2026, 2, 5)),
    ]
    result = _analytics(premises, contracts)
    assert [row["contract_id"] for row in result["expiring_contracts"]] == [1, 2]


# --------------------------------------------------------------------------- #
# Incidents and overdue indicators
# --------------------------------------------------------------------------- #
def test_open_critical_requests_counted() -> None:
    requests = [
        {"priority": "CRITICAL", "status": "OPEN"},
        {"priority": "CRITICAL", "status": "IN_PROGRESS"},
        {"priority": "CRITICAL", "status": "RESOLVED"},
        {"priority": "HIGH", "status": "OPEN"},
    ]
    result = _analytics([], [], [], requests)
    assert result["open_critical_requests"] == 2


def test_overdue_payments_indicator() -> None:
    payments = [
        {"amount_due": Decimal("100.00"), "paid_amount": Decimal("0.00"), "status": "OVERDUE"},
        {"amount_due": Decimal("100.00"), "paid_amount": Decimal("40.00"), "status": "OVERDUE"},
        {"amount_due": Decimal("100.00"), "paid_amount": Decimal("0.00"), "status": "PENDING"},
    ]
    result = _analytics([], [], payments)
    assert result["overdue_payments_count"] == 2
    assert result["overdue_payments_amount"] == Decimal("160.00")


# --------------------------------------------------------------------------- #
# CSV export
# --------------------------------------------------------------------------- #
def test_csv_rows_sorted_and_shaped() -> None:
    premises = [_make_premises(1, "OFFICE", "50.00", 2)]
    tenants = [
        {
            "id": 1,
            "company_name": 'ООО "Тест"',
            "inn": "7701234567",
            "contact_person": "Иванов",
            "phone": "1",
            "email": "a@b.ru",
            "is_active": True,
        }
    ]
    contracts = [
        _make_contract(2, 1, date(2026, 1, 1), date(2026, 12, 31), rent="100000.00"),
        _make_contract(1, 1, date(2025, 1, 1), date(2025, 12, 31), rent="90000.00"),
    ]
    rows = contracts_to_csv_rows(contracts, premises, tenants)
    assert len(rows) == 2
    assert rows[0][0] == "BC-0001"  # sorted by contract number
    assert rows[0][1] == "T-1"
    assert rows[0][2] == "OFFICE"
    assert rows[0][3] == "2"
    assert rows[0][4] == 'ООО "Тест"'
    assert rows[0][5] == "7701234567"
    assert rows[0][6] == "2025-01-01"
    assert rows[0][7] == "2025-12-31"
    assert rows[0][8] == "90000.00"
    assert rows[0][9] == "ACTIVE"


def test_csv_rows_missing_references() -> None:
    contracts = [_make_contract(1, 999, date(2026, 1, 1), date(2026, 12, 31))]
    rows = contracts_to_csv_rows(contracts, [], [])
    assert rows[0][1] == ""
    assert rows[0][4] == ""


def test_csv_rows_empty() -> None:
    assert contracts_to_csv_rows([], [], []) == []


def test_money_helper() -> None:
    assert analytics.money("1.005") == Decimal("1.01")
