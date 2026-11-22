"""Tests for ``src.billing_algorithms`` (Algorithm 4)."""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any

import pytest
from src import billing_algorithms
from src.billing_algorithms import (
    apply_payment,
    calculate_penalties_and_debt,
    calculate_penalty,
)


# --------------------------------------------------------------------------- #
# Penalty calculation
# --------------------------------------------------------------------------- #
def test_penalty_zero_days() -> None:
    result = calculate_penalty(Decimal("100000.00"), 0)
    assert result["penalty"] == Decimal("0.00")
    assert result["capped"] is False
    assert result["rate"] == Decimal("0.00")


def test_penalty_negative_days() -> None:
    result = calculate_penalty(Decimal("100000.00"), -5)
    assert result["penalty"] == Decimal("0.00")


def test_penalty_zero_debt() -> None:
    result = calculate_penalty(Decimal("0.00"), 30)
    assert result["penalty"] == Decimal("0.00")
    assert result["capped"] is False


def test_penalty_negative_debt() -> None:
    # Overpayment must never produce a penalty.
    result = calculate_penalty(Decimal("-500.00"), 40)
    assert result["penalty"] == Decimal("0.00")


@pytest.mark.parametrize(
    ("days", "expected"),
    [
        (1, "100.00"),
        (10, "1000.00"),
        (20, "2000.00"),
        (30, "3000.00"),
        (31, "3200.00"),
        (40, "5000.00"),
    ],
)
def test_penalty_progressive_rates(days: int, expected: str) -> None:
    result = calculate_penalty(Decimal("100000.00"), days)
    assert result["penalty"] == Decimal(expected)
    assert result["capped"] is False


def test_penalty_40_days_rate_is_5_percent() -> None:
    result = calculate_penalty(Decimal("100000.00"), 40)
    assert result["rate"] == Decimal("5.00")


def test_penalty_cap_at_25_percent() -> None:
    result = calculate_penalty(Decimal("100000.00"), 300)
    assert result["penalty"] == Decimal("25000.00")
    assert result["capped"] is True
    assert result["rate"] == Decimal("25.00")


def test_penalty_exactly_at_cap_boundary() -> None:
    # 0.1% * 30 + 0.2% * x = 25%  ->  x = 110 extra days -> 140 days total.
    capped = calculate_penalty(Decimal("100000.00"), 140)
    assert capped["penalty"] == Decimal("25000.00")
    # Just below the boundary: 139 days -> 24.8%.
    below = calculate_penalty(Decimal("100000.00"), 139)
    assert below["penalty"] == Decimal("24800.00")
    assert below["capped"] is False


def test_penalty_partial_debt() -> None:
    result = calculate_penalty(Decimal("60000.00"), 40)
    assert result["penalty"] == Decimal("3000.00")
    assert result["rate"] == Decimal("5.00")


def test_penalty_rounds_half_up() -> None:
    # 10000 * 0.001 * 1 = 10.00; use an amount producing a half-kopeck.
    result = calculate_penalty(Decimal("1010.05"), 1)
    assert result["penalty"] == Decimal("1.01")


# --------------------------------------------------------------------------- #
# Aggregate debt / overdue recalculation
# --------------------------------------------------------------------------- #
def test_aggregate_empty_payments() -> None:
    result = calculate_penalties_and_debt([], date(2026, 1, 15))
    assert result["payments"] == []
    assert result["contracts"] == []
    assert result["total_debt"] == Decimal("0.00")
    assert result["total_penalty"] == Decimal("0.00")


def test_aggregate_includes_zero_rows_for_contracts(
    make_payment: Any, make_contract: Any
) -> None:
    contracts = [make_contract(id=1), make_contract(id=2)]
    result = calculate_penalties_and_debt([], date(2026, 1, 15), contracts)
    assert [row["contract_id"] for row in result["contracts"]] == [1, 2]
    assert all(row["debt"] == Decimal("0.00") for row in result["contracts"])


def test_aggregate_skips_paid_payments(make_payment: Any) -> None:
    payments = [
        make_payment(id=1, status="PAID", due_date=date(2025, 12, 1)),
    ]
    result = calculate_penalties_and_debt(payments, date(2026, 1, 15))
    assert result["payments"] == []
    assert result["total_debt"] == Decimal("0.00")


def test_aggregate_skips_future_due_date(make_payment: Any) -> None:
    payments = [make_payment(id=1, due_date=date(2026, 2, 5), status="PENDING")]
    result = calculate_penalties_and_debt(payments, date(2026, 1, 15))
    assert result["payments"] == []


def test_aggregate_due_date_equal_to_calc_date_not_overdue(make_payment: Any) -> None:
    payments = [make_payment(id=1, due_date=date(2026, 1, 15), status="PENDING")]
    result = calculate_penalties_and_debt(payments, date(2026, 1, 15))
    assert result["payments"] == []


def test_aggregate_single_overdue_payment(make_payment: Any) -> None:
    payments = [
        make_payment(
            id=1,
            contract_id=1,
            due_date=date(2025, 12, 6),
            amount_due=Decimal("100000.00"),
        )
    ]
    result = calculate_penalties_and_debt(payments, date(2026, 1, 15))
    assert len(result["payments"]) == 1
    detail = result["payments"][0]
    assert detail["overdue_days"] == 40
    assert detail["debt"] == Decimal("100000.00")
    assert detail["penalty"] == Decimal("5000.00")
    assert detail["status"] == "OVERDUE"
    assert result["total_debt"] == Decimal("100000.00")
    assert result["total_penalty"] == Decimal("5000.00")


def test_aggregate_partial_payment_keeps_remainder(make_payment: Any) -> None:
    payments = [
        make_payment(
            id=1,
            due_date=date(2025, 12, 6),
            amount_due=Decimal("100000.00"),
            paid_amount=Decimal("40000.00"),
            status="PARTIAL",
        )
    ]
    result = calculate_penalties_and_debt(payments, date(2026, 1, 15))
    assert result["payments"][0]["debt"] == Decimal("60000.00")
    assert result["payments"][0]["penalty"] == Decimal("3000.00")
    assert result["total_debt"] == Decimal("60000.00")


def test_aggregate_groups_by_contract_sorted(make_payment: Any) -> None:
    payments = [
        make_payment(id=5, contract_id=2, due_date=date(2025, 12, 1)),
        make_payment(id=1, contract_id=1, due_date=date(2025, 12, 1)),
        make_payment(id=2, contract_id=1, due_date=date(2025, 11, 1)),
    ]
    result = calculate_penalties_and_debt(payments, date(2026, 1, 15))
    assert [row["contract_id"] for row in result["contracts"]] == [1, 2]
    assert result["contracts"][0]["overdue_payments"] == 2
    # Details are sorted by (contract_id, payment_id).
    assert [row["payment_id"] for row in result["payments"]] == [1, 2, 5]


def test_aggregate_contract_without_zero_rows_when_not_passed(make_payment: Any) -> None:
    payments = [make_payment(id=1, contract_id=3, due_date=date(2025, 12, 1))]
    result = calculate_penalties_and_debt(payments, date(2026, 1, 15))
    assert [row["contract_id"] for row in result["contracts"]] == [3]


# --------------------------------------------------------------------------- #
# Applying payments
# --------------------------------------------------------------------------- #
def test_apply_payment_partial(make_payment: Any) -> None:
    payment = make_payment(amount_due=Decimal("100000.00"))
    updated = apply_payment(payment, Decimal("30000.00"), date(2026, 1, 10))
    assert updated["paid_amount"] == Decimal("30000.00")
    assert updated["status"] == "PARTIAL"
    assert updated["paid_date"] == date(2026, 1, 10)
    # Original payment must remain untouched (pure function).
    assert payment["paid_amount"] == Decimal("0.00")
    assert payment["status"] == "PENDING"


def test_apply_payment_full(make_payment: Any) -> None:
    payment = make_payment(amount_due=Decimal("100000.00"))
    updated = apply_payment(payment, Decimal("100000.00"), date(2026, 1, 10))
    assert updated["paid_amount"] == Decimal("100000.00")
    assert updated["status"] == "PAID"


def test_apply_payment_accumulates(make_payment: Any) -> None:
    payment = make_payment(
        amount_due=Decimal("100000.00"),
        paid_amount=Decimal("70000.00"),
        status="PARTIAL",
    )
    updated = apply_payment(payment, Decimal("30000.00"), date(2026, 1, 10))
    assert updated["status"] == "PAID"


def test_apply_payment_overpayment_counts_as_paid(make_payment: Any) -> None:
    payment = make_payment(amount_due=Decimal("100000.00"))
    updated = apply_payment(payment, Decimal("120000.00"), date(2026, 1, 10))
    assert updated["status"] == "PAID"
    assert updated["paid_amount"] == Decimal("120000.00")


def test_apply_payment_rounds_to_kopecks(make_payment: Any) -> None:
    payment = make_payment(amount_due=Decimal("100000.00"))
    updated = apply_payment(payment, Decimal("0.005"), date(2026, 1, 10))
    assert updated["paid_amount"] == Decimal("0.01")


def test_money_helper() -> None:
    assert billing_algorithms.money("99.995") == Decimal("100.00")
