"""Billing algorithms: overdue debt and penalty calculation.

Procedural implementation of Algorithm 4 from Plan.md, section 4.1.

Penalty rules: 0.1% per day for the first 30 overdue days, 0.2% per day for
every subsequent day, capped at 25% of the outstanding debt.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any

MONEY_QUANTUM = Decimal("0.01")

PENALTY_DAILY_BASE = Decimal("0.001")
PENALTY_DAILY_EXTRA = Decimal("0.002")
PENALTY_BASE_DAYS = 30
PENALTY_CAP_RATE = Decimal("0.25")

UNPAID_STATUSES = ("PENDING", "PARTIAL", "OVERDUE")


def money(value: Decimal | int | float | str) -> Decimal:
    """Quantize a value to kopecks using ``ROUND_HALF_UP``.

    Args:
        value: Any numeric value convertible to ``Decimal``.

    Returns:
        Money amount rounded to 2 digits.
    """
    return Decimal(str(value)).quantize(MONEY_QUANTUM, rounding="ROUND_HALF_UP")


def calculate_penalty(debt: Decimal, overdue_days: int) -> dict[str, Any]:
    """Calculate the penalty amount for an outstanding debt.

    Args:
        debt: Outstanding amount (``amount_due - paid_amount``).
        overdue_days: Number of days past the due date.

    Returns:
        Dict with ``penalty``, ``capped`` and ``rate`` (effective percentage
        of the debt, rounded to 2 digits).
    """
    if debt <= 0 or overdue_days <= 0:
        return {"penalty": money(0), "capped": False, "rate": Decimal("0.00")}

    base_days = min(overdue_days, PENALTY_BASE_DAYS)
    extra_days = max(0, overdue_days - PENALTY_BASE_DAYS)
    raw_rate = PENALTY_DAILY_BASE * base_days + PENALTY_DAILY_EXTRA * extra_days
    raw_penalty = debt * raw_rate
    cap = debt * PENALTY_CAP_RATE

    if raw_penalty > cap:
        penalty = money(cap)
        capped = True
    else:
        penalty = money(raw_penalty)
        capped = False

    effective_rate = (penalty / debt * Decimal(100)).quantize(
        MONEY_QUANTUM, rounding="ROUND_HALF_UP"
    )
    return {"penalty": penalty, "capped": capped, "rate": effective_rate}


def calculate_penalties_and_debt(
    payments: list[dict[str, Any]],
    calculation_date: date,
    contracts: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Recalculate overdue payments, penalties and per-contract debt.

    A payment is overdue when its status is not ``PAID`` and its ``due_date``
    is strictly before ``calculation_date``. Partially paid entries keep the
    unpaid remainder as debt.

    Args:
        payments: Normalized payment list.
        calculation_date: Date the calculation is performed on.
        contracts: Optional normalized contracts; when provided the aggregate
            includes every contract (even those without debt).

    Returns:
        Dict with ``payments`` (per-payment detail), ``contracts`` (aggregated
        debt/penalty), ``total_debt`` and ``total_penalty``.
    """
    details: list[dict[str, Any]] = []
    by_contract: dict[int, dict[str, Any]] = {}

    if contracts:
        for contract in contracts:
            by_contract[contract["id"]] = {
                "contract_id": contract["id"],
                "debt": Decimal("0.00"),
                "penalty": Decimal("0.00"),
                "overdue_payments": 0,
            }

    for payment in payments:
        if payment["status"] == "PAID":
            continue
        if payment["due_date"] >= calculation_date:
            continue

        overdue_days = (calculation_date - payment["due_date"]).days
        debt = payment["amount_due"] - payment["paid_amount"]
        result = calculate_penalty(debt, overdue_days)

        details.append(
            {
                "payment_id": payment["id"],
                "contract_id": payment["contract_id"],
                "period_number": payment["period_number"],
                "due_date": payment["due_date"],
                "overdue_days": overdue_days,
                "debt": money(debt),
                "penalty": result["penalty"],
                "rate": result["rate"],
                "capped": result["capped"],
                "status": "OVERDUE",
            }
        )

        aggregate = by_contract.setdefault(
            payment["contract_id"],
            {
                "contract_id": payment["contract_id"],
                "debt": Decimal("0.00"),
                "penalty": Decimal("0.00"),
                "overdue_payments": 0,
            },
        )
        aggregate["debt"] += money(debt)
        aggregate["penalty"] += result["penalty"]
        aggregate["overdue_payments"] += 1

    contracts_aggregate: list[dict[str, Any]] = []
    total_debt = Decimal("0.00")
    total_penalty = Decimal("0.00")
    for contract_id in sorted(by_contract):
        aggregate = by_contract[contract_id]
        aggregate["debt"] = money(aggregate["debt"])
        aggregate["penalty"] = money(aggregate["penalty"])
        total_debt += aggregate["debt"]
        total_penalty += aggregate["penalty"]
        contracts_aggregate.append(aggregate)

    details.sort(key=lambda item: (item["contract_id"], item["payment_id"]))
    return {
        "calculation_date": calculation_date,
        "payments": details,
        "contracts": contracts_aggregate,
        "total_debt": money(total_debt),
        "total_penalty": money(total_penalty),
    }


def apply_payment(
    payment: dict[str, Any],
    amount: Decimal,
    paid_date: date,
) -> dict[str, Any]:
    """Register an (possibly partial) payment against a schedule entry.

    Args:
        payment: Normalized payment dict.
        amount: Amount paid in this transaction.
        paid_date: Date the payment was made.

    Returns:
        New payment dict with updated ``paid_amount``, ``paid_date`` and
        ``status`` (``PAID`` or ``PARTIAL``).
    """
    paid_total = money(payment["paid_amount"] + amount)
    status = "PAID" if paid_total >= payment["amount_due"] else "PARTIAL"
    updated = dict(payment)
    updated["paid_amount"] = paid_total
    updated["paid_date"] = paid_date
    updated["status"] = status
    return updated
