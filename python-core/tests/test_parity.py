"""Parity tests: Python procedural core vs ``shared-fixtures/expected_results.json``.

Session 3 requirement: verify that the six algorithms produce exactly the
reference values (money as fixed 2-digit strings, ordered ranked lists).
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from src import cli, data_loader


@pytest.fixture(scope="module")
def report(dataset_path: Path) -> dict[str, Any]:
    """Full algorithm report computed on the shared dataset."""
    return cli.run_algorithms(data_loader.load_dataset(dataset_path))


@pytest.fixture(scope="module")
def expected(expected_path: Path) -> dict[str, Any]:
    """Reference results from ``expected_results.json``."""
    with expected_path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


# --------------------------------------------------------------------------- #
# Whole-report parity
# --------------------------------------------------------------------------- #
def test_verify_command_passes() -> None:
    result = cli.verify()
    assert result["passed"] is True, result["differences"]
    assert result["diff_count"] == 0


def test_report_has_all_algorithm_sections(report: dict[str, Any]) -> None:
    assert set(report) == {
        "algorithm_1_lease_conflicts",
        "algorithm_2_match_and_rank",
        "algorithm_3_rent_and_schedule",
        "algorithm_4_penalties",
        "algorithm_4_debt_aggregate",
        "algorithm_5_maintenance_queue",
        "algorithm_5_sla_weights",
        "algorithm_6_analytics",
    }


# --------------------------------------------------------------------------- #
# Algorithm 1
# --------------------------------------------------------------------------- #
def test_parity_algorithm_1(report: dict[str, Any], expected: dict[str, Any]) -> None:
    assert report["algorithm_1_lease_conflicts"] == expected["algorithm_1_lease_conflicts"]


def test_algorithm_1_names_order(report: dict[str, Any]) -> None:
    names = [row["name"] for row in report["algorithm_1_lease_conflicts"]]
    assert names == [
        "overlap_1_day",
        "adjacent_next_day",
        "exclude_self",
        "completed_ignored",
    ]


# --------------------------------------------------------------------------- #
# Algorithm 2
# --------------------------------------------------------------------------- #
def test_parity_algorithm_2(report: dict[str, Any], expected: dict[str, Any]) -> None:
    actual = report["algorithm_2_match_and_rank"]
    reference = expected["algorithm_2_match_and_rank"]
    assert len(actual) == len(reference)
    for actual_row, expected_row in zip(actual, reference, strict=True):
        assert actual_row["name"] == expected_row["name"]
        assert actual_row["candidates"] == expected_row["candidates"]


def test_algorithm_2_office_scenario_is_empty(report: dict[str, Any]) -> None:
    office = next(
        row
        for row in report["algorithm_2_match_and_rank"]
        if row["name"] == "office_85_sqm"
    )
    assert office["candidates"] == []


def test_algorithm_2_candidates_are_ranked(report: dict[str, Any]) -> None:
    for scenario in report["algorithm_2_match_and_rank"]:
        ranks = [row["rank"] for row in scenario["candidates"]]
        assert ranks == list(range(1, len(ranks) + 1)), scenario["name"]


# --------------------------------------------------------------------------- #
# Algorithm 3
# --------------------------------------------------------------------------- #
def test_parity_algorithm_3(report: dict[str, Any], expected: dict[str, Any]) -> None:
    actual = report["algorithm_3_rent_and_schedule"]
    reference = expected["algorithm_3_rent_and_schedule"]
    assert len(actual) == len(reference)
    for actual_row, expected_row in zip(actual, reference, strict=True):
        assert actual_row["name"] == expected_row["name"]
        for key, value in expected_row.items():
            if key == "verification_notes":
                continue
            assert actual_row[key] == value, f"{expected_row['name']}.{key}"


def test_algorithm_3_schedule_length_matches_months(report: dict[str, Any]) -> None:
    for row in report["algorithm_3_rent_and_schedule"]:
        assert row["schedule_entries"] == row["months"], row["name"]
        assert len(row["schedule"]) == row["months"], row["name"]


def test_algorithm_3_net_plus_vat_equals_total(report: dict[str, Any]) -> None:
    from decimal import Decimal

    for row in report["algorithm_3_rent_and_schedule"]:
        net = Decimal(row["net_monthly"])
        vat = Decimal(row["vat_amount"])
        total = Decimal(row["total_monthly"])
        assert net + vat == total, row["name"]


# --------------------------------------------------------------------------- #
# Algorithm 4
# --------------------------------------------------------------------------- #
def test_parity_algorithm_4_penalties(
    report: dict[str, Any], expected: dict[str, Any]
) -> None:
    assert report["algorithm_4_penalties"] == expected["algorithm_4_penalties"]


def test_parity_algorithm_4_debt_aggregate(
    report: dict[str, Any], expected: dict[str, Any]
) -> None:
    actual = report["algorithm_4_debt_aggregate"]
    reference = expected["algorithm_4_debt_aggregate"]
    assert actual["calculation_date"] == reference["calculation_date"]
    assert actual["contracts"] == reference["contracts"]
    assert actual["total_debt"] == reference["total_debt"]
    assert actual["total_penalty"] == reference["total_penalty"]


def test_algorithm_4_cap_applied(report: dict[str, Any]) -> None:
    cap_case = next(
        row for row in report["algorithm_4_penalties"] if row["name"] == "days_300_cap"
    )
    assert cap_case["capped"] is True
    assert cap_case["penalty"] == "25000.00"


# --------------------------------------------------------------------------- #
# Algorithm 5
# --------------------------------------------------------------------------- #
def test_parity_algorithm_5_queue(
    report: dict[str, Any], expected: dict[str, Any]
) -> None:
    assert report["algorithm_5_maintenance_queue"] == expected["algorithm_5_maintenance_queue"]


def test_parity_algorithm_5_sla(report: dict[str, Any], expected: dict[str, Any]) -> None:
    assert report["algorithm_5_sla_weights"] == expected["algorithm_5_sla_weights"]


def test_algorithm_5_queue_positions_are_sequential(report: dict[str, Any]) -> None:
    positions = [row["queue_position"] for row in report["algorithm_5_maintenance_queue"]]
    assert positions == list(range(1, len(positions) + 1))


def test_algorithm_5_excludes_resolved(report: dict[str, Any], dataset: dict[str, Any]) -> None:
    active_ids = {
        request["id"]
        for request in dataset["maintenance_requests"]
        if request["status"] in ("OPEN", "IN_PROGRESS")
    }
    queue_ids = {row["id"] for row in report["algorithm_5_maintenance_queue"]}
    assert queue_ids == active_ids


# --------------------------------------------------------------------------- #
# Algorithm 6
# --------------------------------------------------------------------------- #
def test_parity_algorithm_6(report: dict[str, Any], expected: dict[str, Any]) -> None:
    actual = report["algorithm_6_analytics"]
    reference = expected["algorithm_6_analytics"]
    for key, value in reference.items():
        if key == "verification_notes":
            continue
        assert actual[key] == value, key


def test_algorithm_6_occupied_area_not_above_total(report: dict[str, Any]) -> None:
    from decimal import Decimal

    summary = report["algorithm_6_analytics"]
    assert Decimal(summary["occupied_area_sqm"]) <= Decimal(summary["total_area_sqm"])
    assert Decimal("0") <= Decimal(summary["occupancy_percent"]) <= Decimal("100")


# --------------------------------------------------------------------------- #
# Cross-cutting
# --------------------------------------------------------------------------- #
def test_no_user_defined_classes_in_src() -> None:
    """Procedural core must not declare user classes in ``src/``."""
    import re

    src_dir = Path(__file__).resolve().parent.parent / "src"
    pattern = re.compile(r"^\s*class\s+\w+", re.MULTILINE)
    offenders = [
        path.name
        for path in src_dir.glob("*.py")
        if pattern.search(path.read_text(encoding="utf-8"))
    ]
    assert offenders == []


def test_all_money_values_are_two_digit_strings(report: dict[str, Any]) -> None:
    import re

    money_keys = {
        "base_monthly",
        "typed_monthly",
        "opex_monthly",
        "net_monthly",
        "vat_amount",
        "total_monthly",
        "amount_due",
        "estimated_monthly_cost",
        "mrr",
        "total_debt",
        "total_penalty",
        "debt",
        "penalty",
    }
    money_format = re.compile(r"^-?\d+\.\d{2}$")

    def walk(node: Any) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                if key in money_keys and isinstance(value, str):
                    assert money_format.match(value), f"{key}={value!r}"
                else:
                    walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(report)
