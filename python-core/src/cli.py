"""Command-line entry point for the BC-Manager procedural core.

Commands:
    run       Run every algorithm on ``shared-fixtures/test_dataset.json``
              and print a JSON report.
    bench     Measure execution time on synthetic datasets (1000 / 10000
              records) to compare later with the Java implementation.
    verify    Compare the computed results with
              ``shared-fixtures/expected_results.json``.

Usage::

    python -m src.cli run  --dataset ../shared-fixtures/test_dataset.json
    python -m src.cli bench
    python -m src.cli verify
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Any

if __package__ in (None, ""):
    # Allow running ``python src/cli.py`` directly.
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from src import (
        analytics,
        billing_algorithms,
        data_loader,
        lease_algorithms,
        maintenance_queue,
    )
else:
    from . import (
        analytics,
        billing_algorithms,
        data_loader,
        lease_algorithms,
        maintenance_queue,
    )

ROOT = Path(__file__).resolve().parent.parent.parent
FIXTURES_DIR = ROOT / "shared-fixtures"
DEFAULT_DATASET = FIXTURES_DIR / "test_dataset.json"
DEFAULT_EXPECTED = FIXTURES_DIR / "expected_results.json"


# --------------------------------------------------------------------------- #
# JSON encoding helpers
# --------------------------------------------------------------------------- #
def _json_default(value: Any) -> Any:
    """Serialize ``Decimal``/``date``/``datetime`` values for ``json``.

    Args:
        value: Object that the default encoder cannot handle.

    Returns:
        JSON-serializable representation.
    """
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, datetime):
        return value.strftime("%Y-%m-%dT%H:%M:%S")
    if isinstance(value, date):
        return value.isoformat()
    raise TypeError(f"Object of type {type(value).__name__} is not JSON serializable")


def dumps(payload: Any) -> str:
    """Serialize a payload to pretty JSON with date/Decimal support.

    Args:
        payload: Any JSON-compatible structure.

    Returns:
        Pretty-printed JSON text (UTF-8 friendly, ``ensure_ascii=False``).
    """
    return json.dumps(payload, default=_json_default, ensure_ascii=False, indent=2)


def money_str(value: Decimal) -> str:
    """Render a ``Decimal`` as a fixed 2-digit string.

    Args:
        value: Money amount.

    Returns:
        String with exactly 2 fractional digits.
    """
    return f"{value:.2f}"


# --------------------------------------------------------------------------- #
# Dataset execution
# --------------------------------------------------------------------------- #
def _resolve_scenario_dates(scenario: dict[str, Any]) -> dict[str, Any]:
    """Convert ISO date fields of a scenario into ``date`` objects.

    Args:
        scenario: Raw scenario dict from the fixtures.

    Returns:
        Copy of the scenario with parsed ``start_date``/``end_date``.
    """
    resolved = dict(scenario)
    if isinstance(resolved.get("start_date"), str):
        resolved["start_date"] = data_loader.parse_date(resolved["start_date"])
    if isinstance(resolved.get("end_date"), str):
        resolved["end_date"] = data_loader.parse_date(resolved["end_date"])
    return resolved


def run_algorithms(dataset: dict[str, Any]) -> dict[str, Any]:
    """Execute all six algorithms on a normalized dataset.

    Args:
        dataset: Output of :func:`data_loader.load_dataset`.

    Returns:
        Combined report dict keyed by algorithm.
    """
    premises = dataset["premises"]
    contracts = dataset["contracts"]
    payments = dataset["payments"]
    requests = dataset["maintenance_requests"]
    scenarios = dataset.get("scenarios", {})
    vat_rate = dataset["metadata"]["vat_rate"]
    as_of = dataset["metadata"]["calculation_date"]
    now = dataset["metadata"]["maintenance_now"]

    premises_by_id = {item["id"]: item for item in premises}

    report: dict[str, Any] = {}

    # Algorithm 1
    conflicts = []
    for scenario in scenarios.get("lease_conflict_checks", []):
        resolved = _resolve_scenario_dates(scenario)
        result = lease_algorithms.check_lease_conflict(
            contracts,
            resolved["premises_id"],
            resolved["start_date"],
            resolved["end_date"],
            resolved.get("exclude_contract_id"),
        )
        conflicts.append({"name": scenario["name"], **result})
    report["algorithm_1_lease_conflicts"] = conflicts

    # Algorithm 2
    matches = []
    for scenario in scenarios.get("match_requests", []):
        candidates = lease_algorithms.match_and_rank_premises(
            premises, contracts, scenario, vat_rate
        )
        matches.append(
            {
                "name": scenario["name"],
                "candidates": [
                    {
                        "premises_id": item["premises_id"],
                        "code": item["code"],
                        "area_sqm": money_str(item["area_sqm"]),
                        "estimated_monthly_cost": money_str(
                            item["estimated_monthly_cost"]
                        ),
                        "score": money_str(item["score"]),
                        "rank": item["rank"],
                    }
                    for item in candidates
                ],
            }
        )
    report["algorithm_2_match_and_rank"] = matches

    # Algorithm 3
    pricing_rows = []
    for scenario in scenarios.get("pricing_requests", []):
        resolved = _resolve_scenario_dates(scenario)
        premises_item = premises_by_id[resolved["premises_id"]]
        pricing = lease_algorithms.calculate_rent_and_schedule(
            premises_item, resolved["start_date"], resolved["end_date"], vat_rate
        )
        pricing_rows.append(
            {
                "name": scenario["name"],
                "premises_id": resolved["premises_id"],
                "months": pricing["months"],
                "base_monthly": money_str(pricing["base_monthly"]),
                "typed_monthly": money_str(pricing["typed_monthly"]),
                "opex_monthly": money_str(pricing["opex_monthly"]),
                "discount_percent": money_str(pricing["discount_percent"]),
                "net_monthly": money_str(pricing["net_monthly"]),
                "vat_amount": money_str(pricing["vat_amount"]),
                "total_monthly": money_str(pricing["total_monthly"]),
                "schedule_entries": len(pricing["schedule"]),
                "schedule": [
                    {
                        "period_number": entry["period_number"],
                        "due_date": entry["due_date"].isoformat(),
                        "amount_due": money_str(entry["amount_due"]),
                    }
                    for entry in pricing["schedule"]
                ],
            }
        )
    report["algorithm_3_rent_and_schedule"] = pricing_rows

    # Algorithm 4
    penalty_cases = []
    for scenario in scenarios.get("penalty_cases", []):
        debt = Decimal(str(scenario["amount_due"])) - Decimal(
            str(scenario["paid_amount"])
        )
        overdue_days = (
            data_loader.parse_date(scenario["calculation_date"])
            - data_loader.parse_date(scenario["due_date"])
        ).days
        result = billing_algorithms.calculate_penalty(debt, overdue_days)
        penalty_cases.append(
            {
                "name": scenario["name"],
                "overdue_days": overdue_days,
                "debt": money_str(billing_algorithms.money(debt)),
                "penalty": money_str(result["penalty"]),
                "capped": result["capped"],
                "status": "OVERDUE",
            }
        )
    report["algorithm_4_penalties"] = penalty_cases

    debt_report = billing_algorithms.calculate_penalties_and_debt(
        payments, as_of, contracts
    )
    report["algorithm_4_debt_aggregate"] = {
        "calculation_date": as_of.isoformat(),
        "contracts": [
            {
                "contract_id": row["contract_id"],
                "debt": money_str(row["debt"]),
                "penalty": money_str(row["penalty"]),
                "overdue_payments": row["overdue_payments"],
            }
            for row in debt_report["contracts"]
        ],
        "total_debt": money_str(debt_report["total_debt"]),
        "total_penalty": money_str(debt_report["total_penalty"]),
    }

    # Algorithm 5
    queue = maintenance_queue.prioritize_maintenance_queue(requests, now)
    report["algorithm_5_maintenance_queue"] = [
        {
            "id": item["id"],
            "priority": item["priority"],
            "effective_weight": item["effective_weight"],
            "created_at": item["created_at"].strftime("%Y-%m-%dT%H:%M:%S"),
            "queue_position": item["queue_position"],
            "escalated": item["escalated"],
        }
        for item in queue
    ]

    sla_rows = []
    for scenario in scenarios.get("sla_cases", []):
        info = maintenance_queue.calculate_effective_priority_weight(
            scenario["priority"],
            data_loader.parse_datetime(scenario["created_at"]),
            data_loader.parse_datetime(scenario["now"]),
        )
        sla_rows.append(
            {
                "name": scenario["name"],
                "effective_weight": info["effective_weight"],
            }
        )
    report["algorithm_5_sla_weights"] = sla_rows

    # Algorithm 6
    summary = analytics.compute_occupancy_analytics(
        premises, contracts, payments, requests, as_of
    )
    report["algorithm_6_analytics"] = {
        "as_of": summary["as_of"].isoformat(),
        "total_area_sqm": money_str(summary["total_area_sqm"]),
        "occupied_area_sqm": money_str(summary["occupied_area_sqm"]),
        "occupancy_percent": money_str(summary["occupancy_percent"]),
        "mrr": money_str(summary["mrr"]),
        "average_yield_per_sqm": money_str(summary["average_yield_per_sqm"]),
        "by_type": [
            {
                "type": row["type"],
                "total_area_sqm": money_str(row["total_area_sqm"]),
                "occupied_area_sqm": money_str(row["occupied_area_sqm"]),
                "occupancy_percent": money_str(row["occupancy_percent"]),
            }
            for row in summary["by_type"]
        ],
        "by_floor": [
            {
                "floor": row["floor"],
                "total_area_sqm": money_str(row["total_area_sqm"]),
                "occupied_area_sqm": money_str(row["occupied_area_sqm"]),
                "occupancy_percent": money_str(row["occupancy_percent"]),
            }
            for row in summary["by_floor"]
        ],
        "expiring_contracts_30_days": [
            {
                "contract_id": row["contract_id"],
                "contract_number": row["contract_number"],
                "end_date": row["end_date"].isoformat(),
                "days_left": row["days_left"],
            }
            for row in summary["expiring_contracts"]
        ],
        "open_critical_requests": summary["open_critical_requests"],
    }

    return report


# --------------------------------------------------------------------------- #
# Verification against expected_results.json
# --------------------------------------------------------------------------- #
def _compare_fields(
    actual: dict[str, Any],
    expected: dict[str, Any],
    ignored: set[str],
    path: str,
    diffs: list[str],
) -> None:
    """Recursively compare selected numeric/string fields of two dicts.

    Args:
        actual: Computed structure.
        expected: Reference structure from the fixtures.
        ignored: Keys that are skipped (e.g. ``verification_notes``).
        path: Dotted path used in reports.
        diffs: Accumulator for human-readable differences.
    """
    for key, expected_value in expected.items():
        if key in ignored:
            continue
        current_path = f"{path}.{key}" if path else key
        if key not in actual:
            diffs.append(f"{current_path}: missing in actual")
            continue
        actual_value = actual[key]
        if isinstance(expected_value, dict):
            _compare_fields(actual_value, expected_value, ignored, current_path, diffs)
        elif isinstance(expected_value, list):
            if not isinstance(actual_value, list):
                diffs.append(f"{current_path}: expected list")
                continue
            if len(actual_value) != len(expected_value):
                diffs.append(
                    f"{current_path}: length {len(actual_value)} != {len(expected_value)}"
                )
            for index, item in enumerate(expected_value):
                if index >= len(actual_value):
                    break
                actual_item = actual_value[index]
                if isinstance(item, dict):
                    _compare_fields(
                        actual_item, item, ignored, f"{current_path}[{index}]", diffs
                    )
                elif actual_item != item:
                    diffs.append(
                        f"{current_path}[{index}]: {actual_item!r} != {item!r}"
                    )
        elif actual_value != expected_value:
            diffs.append(f"{current_path}: {actual_value!r} != {expected_value!r}")


IGNORED_KEYS = {
    "verification_notes",
    "metadata",
    "description",
    "derived_from",
    "verified_on",
    "currency",
}


def verify(
    dataset_path: Path = DEFAULT_DATASET,
    expected_path: Path = DEFAULT_EXPECTED,
) -> dict[str, Any]:
    """Compare computed results with the reference fixtures.

    Args:
        dataset_path: Path to ``test_dataset.json``.
        expected_path: Path to ``expected_results.json``.

    Returns:
        Dict with ``passed`` (bool), ``diff_count`` and ``differences``.
    """
    dataset = data_loader.load_dataset(dataset_path)
    actual = run_algorithms(dataset)

    with expected_path.open("r", encoding="utf-8") as handle:
        expected = json.load(handle)

    diffs: list[str] = []
    _compare_fields(actual, expected, IGNORED_KEYS, "", diffs)
    return {"passed": not diffs, "diff_count": len(diffs), "differences": diffs}


# --------------------------------------------------------------------------- #
# Synthetic benchmark
# --------------------------------------------------------------------------- #
def build_synthetic_dataset(records: int, seed: int = 42) -> dict[str, Any]:
    """Generate a synthetic normalized dataset of the requested size.

    Args:
        records: Approximate number of premises to generate.
        seed: Random seed for reproducibility.

    Returns:
        Dataset dict with the same shape as :func:`data_loader.load_dataset`.
    """
    rng = random.Random(seed)
    types = ["OFFICE", "RETAIL", "CONFERENCE", "PARKING"]
    opex = {"OFFICE": 150, "RETAIL": 200, "CONFERENCE": 100, "PARKING": 50}

    premises: list[dict[str, Any]] = []
    contracts: list[dict[str, Any]] = []
    base_day = date(2025, 1, 1)

    for index in range(1, records + 1):
        premises_type = types[index % 4]
        area = Decimal(str(round(rng.uniform(12.0, 140.0), 2)))
        rate = Decimal(str(round(rng.uniform(700.0, 2600.0), 2)))
        premises.append(
            {
                "id": index,
                "code": f"S-{index:05d}",
                "building": f"Корпус {chr(65 + index % 3)}",
                "floor": index % 5,
                "type": premises_type,
                "area_sqm": area,
                "base_rate_per_sqm": rate,
                "status": "RENTED" if index % 2 == 0 else "AVAILABLE",
                "has_furniture": bool(index % 2),
                "has_showcase": bool(index % 3 == 0),
                "capacity_people": index % 40 if premises_type == "CONFERENCE" else None,
                "is_covered": bool(index % 2),
                "description": None,
                "_opex": Decimal(opex[premises_type]),
            }
        )

        if index % 2 == 0:
            start = base_day + timedelta(days=rng.randint(0, 300))
            months = rng.choice([3, 6, 12, 24])
            contracts.append(
                {
                    "id": index // 2,
                    "contract_number": f"SYN-{index // 2:06d}",
                    "premises_id": index,
                    "tenant_id": (index % 100) + 1,
                    "start_date": start,
                    "end_date": lease_algorithms.add_months(start, months) - timedelta(days=1),
                    "monthly_rent": (area * rate).quantize(Decimal("0.01")),
                    "discount_percent": Decimal("5.00"),
                    "deposit_amount": (area * rate).quantize(Decimal("0.01")),
                    "status": "ACTIVE",
                    "created_by": 1,
                }
            )

    payments: list[dict[str, Any]] = []
    for contract in contracts:
        payments.append(
            {
                "id": contract["id"] * 10,
                "contract_id": contract["id"],
                "period_number": 1,
                "due_date": contract["start_date"],
                "amount_due": contract["monthly_rent"],
                "paid_amount": Decimal("0.00"),
                "paid_date": None,
                "penalty_amount": Decimal("0.00"),
                "status": "PENDING",
            }
        )

    requests: list[dict[str, Any]] = []
    priorities = ["CRITICAL", "HIGH", "MEDIUM", "LOW"]
    for index in range(1, max(10, records // 100) + 1):
        requests.append(
            {
                "id": index,
                "premises_id": (index % records) + 1,
                "tenant_id": None,
                "title": f"Заявка {index}",
                "description": "Синтетическая заявка",
                "category": "GENERAL",
                "priority": priorities[index % 4],
                "status": "OPEN",
                "created_at": datetime(2026, 1, 1, 0, 0, 0) + timedelta(hours=index),
                "resolved_at": None,
            }
        )

    # Drop the helper ``_opex`` key so premises match the loader shape.
    for item in premises:
        item.pop("_opex", None)

    return {
        "metadata": {
            "name": f"synthetic-{records}",
            "calculation_date": date(2026, 1, 15),
            "maintenance_now": datetime(2026, 1, 15, 12, 0, 0),
            "currency": "RUB",
            "vat_rate": Decimal("0.20"),
        },
        "premises": premises,
        "tenants": [],
        "contracts": contracts,
        "payments": payments,
        "maintenance_requests": requests,
        "scenarios": {},
    }


def benchmark(sizes: tuple[int, ...] = (1000, 10000), repeats: int = 3) -> dict[str, Any]:
    """Measure execution time of every algorithm on synthetic data.

    Args:
        sizes: Dataset sizes (number of premises) to benchmark.
        repeats: Number of repetitions per size; the minimum is reported.

    Returns:
        Dict mapping ``size`` to per-algorithm timings in milliseconds.
    """
    results: dict[str, Any] = {}
    for size in sizes:
        dataset = build_synthetic_dataset(size)
        premises = dataset["premises"]
        contracts = dataset["contracts"]
        payments = dataset["payments"]
        requests = dataset["maintenance_requests"]
        as_of = dataset["metadata"]["calculation_date"]
        now = dataset["metadata"]["maintenance_now"]

        timings: dict[str, float] = {
            "algorithm_1_conflicts": float("inf"),
            "algorithm_2_matching": float("inf"),
            "algorithm_3_pricing": float("inf"),
            "algorithm_4_penalties": float("inf"),
            "algorithm_5_queue": float("inf"),
            "algorithm_6_analytics": float("inf"),
        }

        for _ in range(repeats):
            started = time.perf_counter()
            lease_algorithms.check_lease_conflict(
                contracts, premises[0]["id"], date(2025, 6, 1), date(2026, 2, 1)
            )
            timings["algorithm_1_conflicts"] = min(
                timings["algorithm_1_conflicts"], time.perf_counter() - started
            )

            started = time.perf_counter()
            lease_algorithms.match_and_rank_premises(
                premises,
                contracts,
                {
                    "type": "OFFICE",
                    "target_area": "80.00",
                    "min_area": "40.00",
                    "max_area": "140.00",
                    "max_budget": "500000.00",
                    "start_date": "2026-03-01",
                    "end_date": "2026-12-31",
                },
            )
            timings["algorithm_2_matching"] = min(
                timings["algorithm_2_matching"], time.perf_counter() - started
            )

            started = time.perf_counter()
            lease_algorithms.calculate_rent_and_schedule(
                premises[0], date(2026, 3, 1), date(2027, 2, 28)
            )
            timings["algorithm_3_pricing"] = min(
                timings["algorithm_3_pricing"], time.perf_counter() - started
            )

            started = time.perf_counter()
            billing_algorithms.calculate_penalties_and_debt(payments, as_of, contracts)
            timings["algorithm_4_penalties"] = min(
                timings["algorithm_4_penalties"], time.perf_counter() - started
            )

            started = time.perf_counter()
            maintenance_queue.prioritize_maintenance_queue(requests, now)
            timings["algorithm_5_queue"] = min(
                timings["algorithm_5_queue"], time.perf_counter() - started
            )

            started = time.perf_counter()
            analytics.compute_occupancy_analytics(
                premises, contracts, payments, requests, as_of
            )
            timings["algorithm_6_analytics"] = min(
                timings["algorithm_6_analytics"], time.perf_counter() - started
            )

        results[f"n_{size}"] = {
            "premises": len(premises),
            "contracts": len(contracts),
            "payments": len(payments),
            "requests": len(requests),
            "timings_ms": {
                name: round(value * 1000.0, 3) for name, value in timings.items()
            },
        }
    return results


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def build_parser() -> argparse.ArgumentParser:
    """Create the command-line argument parser.

    Returns:
        Configured :class:`argparse.ArgumentParser`.
    """
    parser = argparse.ArgumentParser(
        prog="bc-manager-core",
        description="Procedural BC-Manager algorithms (CLI).",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    run_parser = subparsers.add_parser(
        "run", help="Run all algorithms on a dataset and print a JSON report."
    )
    run_parser.add_argument(
        "--dataset", default=str(DEFAULT_DATASET), help="Path to test_dataset.json"
    )
    run_parser.add_argument(
        "--output", default=None, help="Optional path to write the JSON report to"
    )

    verify_parser = subparsers.add_parser(
        "verify", help="Compare results with expected_results.json."
    )
    verify_parser.add_argument("--dataset", default=str(DEFAULT_DATASET))
    verify_parser.add_argument("--expected", default=str(DEFAULT_EXPECTED))

    bench_parser = subparsers.add_parser(
        "bench", help="Benchmark algorithms on synthetic datasets."
    )
    bench_parser.add_argument(
        "--sizes", default="1000,10000", help="Comma-separated dataset sizes"
    )
    bench_parser.add_argument(
        "--repeats", type=int, default=3, help="Repetitions per size (min is kept)"
    )

    return parser


def main(argv: list[str] | None = None) -> int:
    """CLI entry point.

    Args:
        argv: Optional argument list (defaults to ``sys.argv[1:]``).

    Returns:
        Process exit code.
    """
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "run":
        dataset = data_loader.load_dataset(args.dataset)
        report = run_algorithms(dataset)
        text = dumps(report)
        if args.output:
            Path(args.output).write_text(text, encoding="utf-8")
            print(f"Report written to {args.output}")
        else:
            print(text)
        return 0

    if args.command == "verify":
        result = verify(Path(args.dataset), Path(args.expected))
        print(dumps(result))
        return 0 if result["passed"] else 1

    if args.command == "bench":
        sizes = tuple(int(item) for item in args.sizes.split(",") if item.strip())
        results = benchmark(sizes, args.repeats)
        print(dumps(results))
        return 0

    parser.print_help()
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
