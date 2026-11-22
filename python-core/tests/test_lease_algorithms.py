"""Tests for ``src.lease_algorithms`` (Algorithms 1-3)."""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any

import pytest
from src import lease_algorithms
from src.lease_algorithms import (
    add_months,
    calculate_rent,
    calculate_rent_and_schedule,
    check_lease_conflict,
    count_full_months,
    days_inclusive,
    first_month_after,
    generate_payment_schedule,
    match_and_rank_premises,
)


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #
def test_add_months_keeps_day() -> None:
    assert add_months(date(2026, 1, 15), 1) == date(2026, 2, 15)
    assert add_months(date(2026, 1, 15), 12) == date(2027, 1, 15)


def test_add_months_clamps_to_last_day() -> None:
    assert add_months(date(2026, 1, 31), 1) == date(2026, 2, 28)
    assert add_months(date(2024, 1, 31), 1) == date(2024, 2, 29)


def test_add_months_negative() -> None:
    assert add_months(date(2026, 1, 15), -1) == date(2025, 12, 15)
    assert add_months(date(2026, 1, 15), -13) == date(2024, 12, 15)


def test_days_inclusive() -> None:
    assert days_inclusive(date(2026, 1, 1), date(2026, 1, 1)) == 1
    assert days_inclusive(date(2026, 1, 1), date(2026, 1, 31)) == 31


def test_first_month_after() -> None:
    assert first_month_after(date(2026, 1, 15)) == date(2026, 2, 1)
    assert first_month_after(date(2026, 12, 31)) == date(2027, 1, 1)


@pytest.mark.parametrize(
    ("start", "end", "expected"),
    [
        (date(2026, 3, 1), date(2027, 2, 28), 12),
        (date(2026, 3, 1), date(2027, 2, 27), 12),
        (date(2026, 3, 1), date(2027, 3, 27), 13),
        (date(2026, 3, 1), date(2027, 3, 31), 13),
        (date(2026, 3, 1), date(2026, 3, 1), 1),
        (date(2026, 3, 15), date(2026, 3, 1), 0),
        (date(2026, 3, 1), date(2026, 4, 1), 2),
        (date(2026, 3, 31), date(2026, 4, 30), 1),
    ],
)
def test_count_full_months(start: date, end: date, expected: int) -> None:
    assert count_full_months(start, end) == expected


# --------------------------------------------------------------------------- #
# Algorithm 1: conflict check
# --------------------------------------------------------------------------- #
def test_conflict_overlap_one_day(make_contract: Any) -> None:
    contract = make_contract(
        id=1, premises_id=1, start_date=date(2025, 3, 1), end_date=date(2026, 2, 28)
    )
    result = check_lease_conflict(
        [contract], 1, date(2026, 2, 28), date(2026, 6, 30)
    )
    assert result["has_conflict"] is True
    assert result["conflicting_contract_id"] == 1
    assert result["conflicting_contract_number"] == "BC-2026-9001"


def test_conflict_adjacent_day_no_conflict(make_contract: Any) -> None:
    contract = make_contract(
        id=1, premises_id=1, start_date=date(2025, 3, 1), end_date=date(2026, 2, 28)
    )
    result = check_lease_conflict(
        [contract], 1, date(2026, 3, 1), date(2026, 6, 30)
    )
    assert result["has_conflict"] is False
    assert result["conflicting_contract_id"] is None
    assert result["conflicting_contract_number"] is None


def test_conflict_exclude_self(make_contract: Any) -> None:
    contract = make_contract(
        id=5, premises_id=1, start_date=date(2025, 3, 1), end_date=date(2026, 2, 28)
    )
    result = check_lease_conflict(
        [contract], 1, date(2025, 3, 1), date(2026, 2, 28), exclude_contract_id=5
    )
    assert result["has_conflict"] is False


def test_conflict_ignores_completed_and_terminated(make_contract: Any) -> None:
    contracts = [
        make_contract(id=1, status="COMPLETED"),
        make_contract(id=2, status="TERMINATED"),
    ]
    result = check_lease_conflict(
        contracts, 1, date(2026, 1, 1), date(2026, 12, 31)
    )
    assert result["has_conflict"] is False


def test_conflict_counts_draft(make_contract: Any) -> None:
    contracts = [make_contract(id=7, status="DRAFT")]
    result = check_lease_conflict(
        contracts, 1, date(2026, 3, 1), date(2026, 12, 31)
    )
    assert result["has_conflict"] is True
    assert result["conflicting_contract_id"] == 7


def test_conflict_other_premises_ignored(make_contract: Any) -> None:
    contracts = [make_contract(id=1, premises_id=99)]
    result = check_lease_conflict(
        contracts, 1, date(2026, 1, 1), date(2026, 12, 31)
    )
    assert result["has_conflict"] is False


def test_conflict_reports_earliest_start(make_contract: Any) -> None:
    contracts = [
        make_contract(id=2, start_date=date(2026, 5, 1), end_date=date(2026, 8, 1)),
        make_contract(id=1, start_date=date(2026, 1, 1), end_date=date(2026, 4, 1)),
    ]
    result = check_lease_conflict(
        contracts, 1, date(2026, 1, 1), date(2026, 12, 31)
    )
    assert result["conflicting_contract_id"] == 1


def test_conflict_empty_contracts() -> None:
    result = check_lease_conflict([], 1, date(2026, 1, 1), date(2026, 12, 31))
    assert result == {
        "has_conflict": False,
        "conflicting_contract_id": None,
        "conflicting_contract_number": None,
    }


# --------------------------------------------------------------------------- #
# Algorithm 3: rent calculation
# --------------------------------------------------------------------------- #
def test_rent_office_furniture_12m(make_premises: Any) -> None:
    premises = make_premises(
        type="OFFICE", area_sqm=Decimal("45.00"),
        base_rate_per_sqm=Decimal("1150.00"), has_furniture=True,
    )
    result = calculate_rent(premises, date(2026, 3, 1), date(2027, 2, 28))
    assert result["months"] == 12
    assert result["base_monthly"] == Decimal("51750.00")
    assert result["typed_monthly"] == Decimal("56925.00")
    assert result["opex_monthly"] == Decimal("6750.00")
    assert result["discount_percent"] == Decimal("10.00")
    assert result["net_monthly"] == Decimal("57307.50")
    assert result["vat_amount"] == Decimal("11461.50")
    assert result["total_monthly"] == Decimal("68769.00")


def test_rent_retail_showcase_10m(make_premises: Any) -> None:
    premises = make_premises(
        type="RETAIL", area_sqm=Decimal("36.40"),
        base_rate_per_sqm=Decimal("2200.00"), has_showcase=True,
    )
    result = calculate_rent(premises, date(2026, 2, 1), date(2026, 11, 30))
    assert result["months"] == 10
    assert result["typed_monthly"] == Decimal("115115.00")
    assert result["discount_percent"] == Decimal("5.00")
    assert result["net_monthly"] == Decimal("116275.25")
    assert result["total_monthly"] == Decimal("139530.30")


def test_rent_conference_no_discount(make_premises: Any) -> None:
    premises = make_premises(
        type="CONFERENCE", area_sqm=Decimal("28.00"),
        base_rate_per_sqm=Decimal("1500.00"),
    )
    result = calculate_rent(premises, date(2026, 2, 1), date(2026, 4, 30))
    assert result["months"] == 3
    assert result["typed_monthly"] == Decimal("48300.00")
    assert result["opex_monthly"] == Decimal("2800.00")
    assert result["discount_percent"] == Decimal("0.00")
    assert result["total_monthly"] == Decimal("61320.00")


def test_rent_office_without_furniture_has_no_surcharge(make_premises: Any) -> None:
    premises = make_premises(
        type="OFFICE", area_sqm=Decimal("45.00"),
        base_rate_per_sqm=Decimal("1150.00"), has_furniture=False,
    )
    result = calculate_rent(premises, date(2026, 3, 1), date(2026, 5, 31))
    assert result["typed_monthly"] == Decimal("51750.00")
    assert result["discount_percent"] == Decimal("0.00")


def test_rent_discount_boundary_5_months(make_premises: Any) -> None:
    premises = make_premises()
    result = calculate_rent(premises, date(2026, 1, 1), date(2026, 5, 31))
    assert result["months"] == 5
    assert result["discount_percent"] == Decimal("0.00")


def test_rent_discount_boundary_6_months(make_premises: Any) -> None:
    premises = make_premises()
    result = calculate_rent(premises, date(2026, 1, 1), date(2026, 6, 30))
    assert result["months"] == 6
    assert result["discount_percent"] == Decimal("5.00")


def test_rent_discount_boundary_11_vs_12_months(make_premises: Any) -> None:
    premises = make_premises()
    eleven = calculate_rent(premises, date(2026, 1, 1), date(2026, 11, 30))
    twelve = calculate_rent(premises, date(2026, 1, 1), date(2026, 12, 31))
    assert eleven["discount_percent"] == Decimal("5.00")
    assert twelve["discount_percent"] == Decimal("10.00")


def test_rent_net_plus_vat_equals_total(make_premises: Any) -> None:
    premises = make_premises(
        type="RETAIL", area_sqm=Decimal("33.33"),
        base_rate_per_sqm=Decimal("1234.56"), has_showcase=True,
    )
    result = calculate_rent(premises, date(2026, 1, 1), date(2026, 7, 31))
    assert result["net_monthly"] + result["vat_amount"] == result["total_monthly"]


def test_rent_all_primitives_are_decimal(make_premises: Any) -> None:
    result = calculate_rent(make_premises(), date(2026, 1, 1), date(2026, 12, 31))
    for key in (
        "base_monthly",
        "typed_monthly",
        "opex_monthly",
        "discount_percent",
        "net_monthly",
        "vat_amount",
        "total_monthly",
    ):
        assert isinstance(result[key], Decimal), key


def test_rent_single_day_term(make_premises: Any) -> None:
    result = calculate_rent(make_premises(), date(2026, 1, 1), date(2026, 1, 1))
    assert result["months"] == 1
    assert result["discount_percent"] == Decimal("0.00")


# --------------------------------------------------------------------------- #
# Algorithm 3: payment schedule
# --------------------------------------------------------------------------- #
def test_schedule_first_due_is_start_then_fifth() -> None:
    schedule = generate_payment_schedule(
        date(2026, 3, 1), 3, Decimal("1000.00")
    )
    assert [row["due_date"] for row in schedule] == [
        date(2026, 3, 1),
        date(2026, 4, 5),
        date(2026, 5, 5),
    ]
    assert [row["period_number"] for row in schedule] == [1, 2, 3]
    assert all(row["amount_due"] == Decimal("1000.00") for row in schedule)


def test_schedule_zero_months() -> None:
    assert generate_payment_schedule(date(2026, 3, 1), 0, Decimal("100")) == []


def test_schedule_and_rent_combined(make_premises: Any) -> None:
    premises = make_premises(
        type="PARKING", area_sqm=Decimal("14.00"),
        base_rate_per_sqm=Decimal("900.00"), is_covered=True,
    )
    result = calculate_rent_and_schedule(
        premises, date(2026, 2, 1), date(2026, 8, 31)
    )
    assert result["months"] == 7
    assert result["total_monthly"] == Decimal("16885.68")
    assert len(result["schedule"]) == 7
    assert result["schedule"][0]["due_date"] == date(2026, 2, 1)
    assert result["schedule"][-1]["due_date"] == date(2026, 8, 5)


# --------------------------------------------------------------------------- #
# Algorithm 2: matching and ranking
# --------------------------------------------------------------------------- #
def _request(**overrides: Any) -> dict[str, Any]:
    criteria: dict[str, Any] = {
        "type": "OFFICE",
        "target_area": "60.00",
        "min_area": "40.00",
        "max_area": "100.00",
        "max_budget": "1000000.00",
        "start_date": "2026-03-01",
        "end_date": "2026-12-31",
    }
    criteria.update(overrides)
    return criteria


def test_match_returns_empty_when_no_available(make_premises: Any) -> None:
    premises = [make_premises(status="RENTED"), make_premises(id=2, status="MAINTENANCE")]
    assert match_and_rank_premises(premises, [], _request()) == []


def test_match_excludes_maintenance(make_premises: Any) -> None:
    premises = [
        make_premises(id=1, status="AVAILABLE"),
        make_premises(id=2, status="MAINTENANCE"),
    ]
    result = match_and_rank_premises(premises, [], _request())
    assert [row["premises_id"] for row in result] == [1]


def test_match_excludes_conflicting_premises(make_premises: Any, make_contract: Any) -> None:
    premises = [make_premises(id=1, status="AVAILABLE")]
    contracts = [make_contract(id=1, premises_id=1, status="ACTIVE")]
    assert match_and_rank_premises(premises, contracts, _request()) == []


def test_match_filters_by_type(make_premises: Any) -> None:
    premises = [
        make_premises(id=1, type="OFFICE"),
        make_premises(id=2, type="RETAIL"),
    ]
    result = match_and_rank_premises(premises, [], _request(type="RETAIL"))
    assert [row["premises_id"] for row in result] == [2]


def test_match_filters_by_area_range(make_premises: Any) -> None:
    premises = [
        make_premises(id=1, area_sqm=Decimal("30.00")),
        make_premises(id=2, area_sqm=Decimal("60.00")),
        make_premises(id=3, area_sqm=Decimal("120.00")),
    ]
    result = match_and_rank_premises(
        premises, [], _request(min_area="40.00", max_area="100.00")
    )
    assert [row["premises_id"] for row in result] == [2]


def test_match_zero_budget_excludes_everything(make_premises: Any) -> None:
    premises = [make_premises()]
    result = match_and_rank_premises(premises, [], _request(max_budget="0"))
    assert result == []


def test_match_ranking_order_and_tiebreak(make_premises: Any) -> None:
    # Same area, different rates -> cheaper wins a higher score.
    premises = [
        make_premises(id=2, area_sqm=Decimal("60.00"), base_rate_per_sqm=Decimal("2000.00")),
        make_premises(id=1, area_sqm=Decimal("60.00"), base_rate_per_sqm=Decimal("1000.00")),
    ]
    result = match_and_rank_premises(premises, [], _request())
    assert [row["rank"] for row in result] == [1, 2]
    assert result[0]["premises_id"] == 1
    assert result[0]["score"] >= result[1]["score"]


def test_match_tiebreak_by_id(make_premises: Any) -> None:
    # Identical cost and area => deterministic order by id asc.
    premises = [
        make_premises(id=9, area_sqm=Decimal("60.00"), base_rate_per_sqm=Decimal("1000.00")),
        make_premises(id=3, area_sqm=Decimal("60.00"), base_rate_per_sqm=Decimal("1000.00")),
    ]
    result = match_and_rank_premises(premises, [], _request())
    assert [row["premises_id"] for row in result] == [3, 9]


def test_match_score_clamped_to_0_100(make_premises: Any) -> None:
    premises = [
        make_premises(
            id=1, area_sqm=Decimal("60.00"), base_rate_per_sqm=Decimal("99999.00")
        )
    ]
    result = match_and_rank_premises(
        premises, [], _request(max_budget="100000000")
    )
    assert len(result) == 1
    assert Decimal("0") <= result[0]["score"] <= Decimal("100")


def test_match_accepts_date_objects(make_premises: Any) -> None:
    premises = [make_premises()]
    request = _request()
    request["start_date"] = date(2026, 3, 1)
    request["end_date"] = date(2026, 12, 31)
    result = match_and_rank_premises(premises, [], request)
    assert len(result) == 1


def test_match_missing_dates_raises(make_premises: Any) -> None:
    request = _request()
    request["start_date"] = None
    with pytest.raises(ValueError):
        match_and_rank_premises([make_premises()], [], request)


def test_match_conflict_on_boundary_day(make_premises: Any, make_contract: Any) -> None:
    premises = [make_premises(id=1, status="AVAILABLE")]
    contracts = [
        make_contract(
            id=1, premises_id=1, status="ACTIVE",
            start_date=date(2026, 12, 31), end_date=date(2027, 6, 30),
        )
    ]
    # Requested window ends 2026-12-31 -> overlap on that single boundary day.
    assert match_and_rank_premises(premises, contracts, _request()) == []


def test_match_empty_premises() -> None:
    assert match_and_rank_premises([], [], _request()) == []


def test_estimate_monthly_cost_matches_pricing(make_premises: Any) -> None:
    premises = make_premises(
        type="OFFICE", area_sqm=Decimal("45.00"),
        base_rate_per_sqm=Decimal("1150.00"), has_furniture=True,
    )
    cost = lease_algorithms.estimate_monthly_cost(
        premises, date(2026, 3, 1), date(2027, 2, 28)
    )
    assert cost == Decimal("68769.00")


def test_money_helper() -> None:
    assert lease_algorithms.money("10.995") == Decimal("11.00")
    assert lease_algorithms.money(Decimal("0.125")) == Decimal("0.13")
