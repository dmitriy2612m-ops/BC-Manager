"""Lease algorithms: conflict detection, premises matching, rent pricing.

Procedural implementation (Algorithms 1-3 from Plan.md, section 4.1).
Strictly no user-defined classes: everything operates on dict/list values.

Conventions:
    * money is ``Decimal`` quantized to 2 digits with ``ROUND_HALF_UP``;
    * dates are ``datetime.date``;
    * a premises/contract is a ``dict`` as produced by ``data_loader``.
"""

from __future__ import annotations

from calendar import monthrange
from datetime import date, timedelta
from decimal import Decimal
from typing import Any

MONEY_QUANTUM = Decimal("0.01")

#: Contract statuses participating in the conflict check.
CONFLICT_STATUSES = ("ACTIVE", "DRAFT")

#: Premises statuses eligible for the smart matching algorithm.
MATCHABLE_STATUSES = ("AVAILABLE",)

#: Percent of the base rate applied to each premises type.
TYPE_RATE_FACTOR: dict[str, Decimal] = {
    "OFFICE": Decimal("1.00"),
    "RETAIL": Decimal("1.25"),
    "CONFERENCE": Decimal("1.15"),
    "PARKING": Decimal("1.00"),
}

#: Surcharge applied to the typed rate when the type-specific flag is set.
TYPE_FEATURE_SURCHARGE: dict[str, tuple[str, Decimal]] = {
    "OFFICE": ("has_furniture", Decimal("0.10")),
    "RETAIL": ("has_showcase", Decimal("0.15")),
    "PARKING": ("is_covered", Decimal("0.12")),
}

#: Operating expense rate per square meter, in RUB.
TYPE_OPEX_PER_SQM: dict[str, Decimal] = {
    "OFFICE": Decimal("150.00"),
    "RETAIL": Decimal("200.00"),
    "CONFERENCE": Decimal("100.00"),
    "PARKING": Decimal("50.00"),
}


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #
def money(value: Decimal | int | float | str) -> Decimal:
    """Quantize a value to kopecks using ``ROUND_HALF_UP``.

    Args:
        value: Any numeric value convertible to ``Decimal``.

    Returns:
        Money amount rounded to 2 digits.
    """
    return Decimal(str(value)).quantize(MONEY_QUANTUM, rounding="ROUND_HALF_UP")


def add_months(source: date, months: int) -> date:
    """Shift a date by a number of calendar months.

    The day of month is kept when possible and clamped to the last day of the
    target month (e.g. ``2026-01-31`` + 1 month = ``2026-02-28``).

    Args:
        source: Original date.
        months: Signed number of months to add.

    Returns:
        Shifted date.
    """
    month_index = source.month - 1 + months
    year = source.year + month_index // 12
    month = month_index % 12 + 1
    last_day = monthrange(year, month)[1]
    return date(year, month, min(source.day, last_day))


def count_full_months(start: date, end: date) -> int:
    """Count full months covered by a lease term ``[start, end]``.

    A term is measured as ``end - start`` plus one inclusive day, then divided
    by the average month length. For the fixtures this yields the expected
    values (e.g. ``2026-03-01``..``2027-02-28`` = 12 months, ``..2026-03-27``
    is still 12 months, ``..2026-03-31`` becomes 13).

    Args:
        start: Lease start date.
        end: Lease end date (inclusive).

    Returns:
        Number of full months (``0`` when the term is shorter than a month).
    """
    if end < start:
        return 0
    months = (end.year - start.year) * 12 + (end.month - start.month)
    # The last (partial) month counts only when the end day is on or after the
    # start day, i.e. the term covers the whole month or more (inclusive end).
    if end.day >= start.day:
        months += 1
    return max(0, months)


def _intervals_overlap(
    start_a: date, end_a: date, start_b: date, end_b: date
) -> bool:
    """Return ``True`` when two inclusive date intervals overlap."""
    return start_a <= end_b and start_b <= end_a


# --------------------------------------------------------------------------- #
# Algorithm 1: lease conflict check
# --------------------------------------------------------------------------- #
def check_lease_conflict(
    contracts: list[dict[str, Any]],
    premises_id: int,
    start_date: date,
    end_date: date,
    exclude_contract_id: int | None = None,
) -> dict[str, Any]:
    """Detect an overlapping lease for a premises within a date interval.

    Only contracts in statuses ``ACTIVE``/``DRAFT`` are considered; the
    contract identified by ``exclude_contract_id`` is skipped so a contract can
    be edited without conflicting with itself.

    Args:
        contracts: Normalized list of contracts.
        premises_id: Premises being booked.
        start_date: Requested lease start (inclusive).
        end_date: Requested lease end (inclusive).
        exclude_contract_id: Contract to ignore (when editing).

    Returns:
        Dict with ``has_conflict``, ``conflicting_contract_id`` and
        ``conflicting_contract_number``.
    """
    best: dict[str, Any] | None = None
    for contract in contracts:
        if contract["premises_id"] != premises_id:
            continue
        if contract["status"] not in CONFLICT_STATUSES:
            continue
        if exclude_contract_id is not None and contract["id"] == exclude_contract_id:
            continue
        if not _intervals_overlap(
            start_date, end_date, contract["start_date"], contract["end_date"]
        ):
            continue
        # Deterministically report the earliest-starting conflicting contract.
        if best is None or contract["start_date"] < best["start_date"]:
            best = contract

    if best is None:
        return {
            "has_conflict": False,
            "conflicting_contract_id": None,
            "conflicting_contract_number": None,
        }
    return {
        "has_conflict": True,
        "conflicting_contract_id": best["id"],
        "conflicting_contract_number": best["contract_number"],
    }


def _contracts_by_premises(
    contracts: list[dict[str, Any]],
) -> dict[int, list[dict[str, Any]]]:
    """Group contracts by ``premises_id`` to speed up conflict checks."""
    grouped: dict[int, list[dict[str, Any]]] = {}
    for contract in contracts:
        grouped.setdefault(contract["premises_id"], []).append(contract)
    return grouped


# --------------------------------------------------------------------------- #
# Algorithm 2: matching and ranking
# --------------------------------------------------------------------------- #
def estimate_monthly_cost(
    premises: dict[str, Any],
    start_date: date,
    end_date: date,
    vat_rate: Decimal = Decimal("0.20"),
) -> Decimal:
    """Estimate the VAT-inclusive monthly rent for a proposed lease.

    Uses the same typed rate, operating expenses and duration discount rules
    as Algorithm 3, without touching the payment schedule.

    Args:
        premises: Normalized premises dict.
        start_date: Lease start date (drives the discount).
        end_date: Lease end date (drives the discount).
        vat_rate: VAT rate applied to the net amount.

    Returns:
        Total monthly rent including VAT, rounded to kopecks.
    """
    pricing = calculate_rent(premises, start_date, end_date, vat_rate)
    return pricing["total_monthly"]


def match_and_rank_premises(
    premises_list: list[dict[str, Any]],
    contracts: list[dict[str, Any]],
    match_request: dict[str, Any],
    vat_rate: Decimal = Decimal("0.20"),
) -> list[dict[str, Any]]:
    """Filter and rank premises by relevance for a tenant request.

    Steps:
        1. Skip ``MAINTENANCE`` premises and those with a lease conflict.
        2. Filter by type, area range and estimated monthly cost.
        3. Score candidates with the weighted formula from the plan.
        4. Sort by score desc, cost asc, id asc.

    Args:
        premises_list: Normalized premises.
        contracts: Normalized contracts.
        match_request: Criteria dict with ``type``, ``target_area``,
            ``min_area``, ``max_area``, ``max_budget``, ``start_date``,
            ``end_date``. Values may be ``Decimal``/``date`` or JSON strings.
        vat_rate: VAT rate used for cost estimation.

    Returns:
        List of candidate dicts containing ``premises_id``, ``code``,
        ``area_sqm``, ``estimated_monthly_cost``, ``score`` and ``rank``.
    """
    if isinstance(match_request.get("start_date"), str):
        from .data_loader import parse_date  # local import avoids a cycle

        start_date = parse_date(match_request["start_date"])
        end_date = parse_date(match_request["end_date"])
    else:
        start_date = match_request["start_date"]
        end_date = match_request["end_date"]

    if start_date is None or end_date is None:
        raise ValueError("match_request must contain start_date and end_date")

    wanted_type = match_request.get("type")
    target_area = Decimal(str(match_request.get("target_area", "0")))
    min_area = Decimal(str(match_request.get("min_area", "0")))
    max_area = Decimal(str(match_request.get("max_area", "1e12")))
    max_budget = Decimal(str(match_request.get("max_budget", "1e12")))

    by_premises = _contracts_by_premises(contracts)
    candidates: list[dict[str, Any]] = []

    for premises in premises_list:
        if premises["status"] not in MATCHABLE_STATUSES:
            continue
        if wanted_type and premises["type"] != wanted_type:
            continue
        area = premises["area_sqm"]
        if area < min_area or area > max_area:
            continue

        conflict = check_lease_conflict(
            by_premises.get(premises["id"], []),
            premises["id"],
            start_date,
            end_date,
        )
        if conflict["has_conflict"]:
            continue

        cost = estimate_monthly_cost(premises, start_date, end_date, vat_rate)
        if cost > max_budget:
            continue

        area_penalty = (
            Decimal(50) * abs(area - target_area) / max(target_area, Decimal(1))
        )
        price_penalty = Decimal(50) * cost / max_budget
        score = Decimal(100) - area_penalty - price_penalty
        score = min(max(score, Decimal(0)), Decimal(100))

        candidates.append(
            {
                "premises_id": premises["id"],
                "code": premises["code"],
                "area_sqm": area,
                "estimated_monthly_cost": cost,
                "score": score.quantize(MONEY_QUANTUM, rounding="ROUND_HALF_UP"),
            }
        )

    candidates.sort(
        key=lambda item: (-item["score"], item["estimated_monthly_cost"], item["premises_id"])
    )
    for rank, candidate in enumerate(candidates, start=1):
        candidate["rank"] = rank
    return candidates


# --------------------------------------------------------------------------- #
# Algorithm 3: rent calculation and payment schedule
# --------------------------------------------------------------------------- #
def _discount_percent(months: int) -> Decimal:
    """Return the duration discount (``0``, ``5`` or ``10`` percent)."""
    if months >= 12:
        return Decimal("10.00")
    if months >= 6:
        return Decimal("5.00")
    return Decimal("0.00")


def calculate_rent(
    premises: dict[str, Any],
    start_date: date,
    end_date: date,
    vat_rate: Decimal = Decimal("0.20"),
) -> dict[str, Any]:
    """Compute the rent breakdown for a premises and lease term.

    The breakdown follows Plan.md section 4.1 (Algorithm 3): base amount, type
    factor, type-specific surcharge, operating expenses, duration discount and
    VAT, with ``HALF_UP`` rounding of the final total.

    Args:
        premises: Normalized premises dict.
        start_date: Lease start date.
        end_date: Lease end date (inclusive).
        vat_rate: VAT rate (``0.20`` by default).

    Returns:
        Dict with ``months``, ``base_monthly``, ``typed_monthly``,
        ``opex_monthly``, ``discount_percent``, ``net_monthly``,
        ``vat_amount`` and ``total_monthly``.
    """
    premises_type = premises["type"]
    area = premises["area_sqm"]
    base = area * premises["base_rate_per_sqm"]

    factor = TYPE_RATE_FACTOR.get(premises_type, Decimal("1.00"))
    typed = base * factor

    feature = TYPE_FEATURE_SURCHARGE.get(premises_type)
    if feature is not None:
        flag_name, surcharge = feature
        if premises.get(flag_name):
            typed = typed + typed * surcharge

    opex = area * TYPE_OPEX_PER_SQM.get(premises_type, Decimal("0"))

    months = count_full_months(start_date, end_date)
    discount = _discount_percent(months)
    net = (typed + opex) * (Decimal(1) - discount / Decimal(100))
    # Round the net monthly amount first, then derive VAT from the rounded net
    # so that ``net + vat`` always equals ``total`` down to the kopeck.
    net = money(net)
    vat_amount = money(net * vat_rate)
    total = money(net + vat_amount)

    return {
        "months": months,
        "base_monthly": money(base),
        "typed_monthly": money(typed),
        "opex_monthly": money(opex),
        "discount_percent": discount,
        "net_monthly": money(net),
        "vat_amount": vat_amount,
        "total_monthly": total,
    }


def generate_payment_schedule(
    start_date: date,
    months: int,
    amount_due: Decimal,
) -> list[dict[str, Any]]:
    """Build a monthly payment schedule for a lease.

    The first period is due on the lease start date, every following period on
    the 5th day of the corresponding month (clamped to the last day of short
    months). All periods carry the same ``amount_due``.

    Args:
        start_date: Lease start date.
        months: Number of monthly periods to generate.
        amount_due: Rent per period (already VAT-inclusive).

    Returns:
        List of dicts with ``period_number``, ``due_date`` and ``amount_due``.
    """
    schedule: list[dict[str, Any]] = []
    for period in range(1, months + 1):
        if period == 1:
            due_date = start_date
        else:
            shifted = add_months(start_date, period - 1)
            last_day = monthrange(shifted.year, shifted.month)[1]
            due_date = date(shifted.year, shifted.month, min(5, last_day))
        schedule.append(
            {
                "period_number": period,
                "due_date": due_date,
                "amount_due": amount_due,
            }
        )
    return schedule


def calculate_rent_and_schedule(
    premises: dict[str, Any],
    start_date: date,
    end_date: date,
    vat_rate: Decimal = Decimal("0.20"),
) -> dict[str, Any]:
    """Combine rent calculation and payment schedule generation.

    Args:
        premises: Normalized premises dict.
        start_date: Lease start date.
        end_date: Lease end date (inclusive).
        vat_rate: VAT rate.

    Returns:
        Rent breakdown extended with a ``schedule`` list of payment dicts.
    """
    pricing = calculate_rent(premises, start_date, end_date, vat_rate)
    pricing["schedule"] = generate_payment_schedule(
        start_date, pricing["months"], pricing["total_monthly"]
    )
    return pricing


def days_inclusive(start_date: date, end_date: date) -> int:
    """Number of days in an inclusive interval.

    Args:
        start_date: Interval start.
        end_date: Interval end.

    Returns:
        ``(end_date - start_date).days + 1``.
    """
    return (end_date - start_date).days + 1


def first_month_after(value: date) -> date:
    """Return the first day of the month following ``value``."""
    return (value.replace(day=1) + timedelta(days=32)).replace(day=1)
