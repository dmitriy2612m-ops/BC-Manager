"""Tests for ``src.maintenance_queue`` (Algorithm 5)."""

from __future__ import annotations

from datetime import datetime
from typing import Any

import pytest
from src.maintenance_queue import (
    build_heap,
    calculate_effective_priority_weight,
    prioritize_maintenance_queue,
    push_request,
)

NOW = datetime(2026, 1, 15, 12, 0, 0)


# --------------------------------------------------------------------------- #
# Effective priority weight
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize(
    ("priority", "expected_weight"),
    [("CRITICAL", 1), ("HIGH", 2), ("MEDIUM", 3), ("LOW", 4)],
)
def test_base_weights_fresh_requests(priority: str, expected_weight: int) -> None:
    info = calculate_effective_priority_weight(priority, NOW, NOW)
    assert info["base_weight"] == expected_weight
    assert info["effective_weight"] == expected_weight
    assert info["escalated"] is False


def test_medium_50h_escalates_to_high() -> None:
    info = calculate_effective_priority_weight(
        "MEDIUM", datetime(2026, 1, 13, 10, 0, 0), NOW
    )
    assert info["waiting_hours"] == pytest.approx(50.0)
    assert info["effective_weight"] == 2
    assert info["escalated"] is True


def test_high_30h_escalates_to_critical() -> None:
    info = calculate_effective_priority_weight(
        "HIGH", datetime(2026, 1, 14, 6, 0, 0), NOW
    )
    assert info["waiting_hours"] == pytest.approx(30.0)
    assert info["effective_weight"] == 1
    assert info["escalated"] is True


def test_medium_exactly_48h_not_escalated() -> None:
    # Escalation is strict (>), so exactly 48h stays MEDIUM.
    info = calculate_effective_priority_weight(
        "MEDIUM", datetime(2026, 1, 13, 12, 0, 0), NOW
    )
    assert info["waiting_hours"] == pytest.approx(48.0)
    assert info["effective_weight"] == 3
    assert info["escalated"] is False


def test_high_exactly_24h_not_escalated() -> None:
    info = calculate_effective_priority_weight(
        "HIGH", datetime(2026, 1, 14, 12, 0, 0), NOW
    )
    assert info["waiting_hours"] == pytest.approx(24.0)
    assert info["effective_weight"] == 2
    assert info["escalated"] is False


def test_medium_just_over_48h_escalates() -> None:
    info = calculate_effective_priority_weight(
        "MEDIUM", datetime(2026, 1, 13, 11, 59, 0), NOW
    )
    assert info["effective_weight"] == 2


def test_low_never_escalates() -> None:
    info = calculate_effective_priority_weight(
        "LOW", datetime(2026, 1, 1, 0, 0, 0), NOW
    )
    assert info["effective_weight"] == 4
    assert info["escalated"] is False


def test_critical_never_escalates() -> None:
    info = calculate_effective_priority_weight(
        "CRITICAL", datetime(2026, 1, 1, 0, 0, 0), NOW
    )
    assert info["effective_weight"] == 1
    assert info["escalated"] is False


def test_unknown_priority_raises_key_error() -> None:
    with pytest.raises(KeyError):
        calculate_effective_priority_weight("URGENT", NOW, NOW)


# --------------------------------------------------------------------------- #
# Queue construction
# --------------------------------------------------------------------------- #
def test_queue_orders_by_weight(make_request: Any) -> None:
    requests = [
        make_request(id=1, priority="LOW"),
        make_request(id=2, priority="CRITICAL"),
        make_request(id=3, priority="HIGH"),
    ]
    queue = prioritize_maintenance_queue(requests, NOW)
    assert [row["id"] for row in queue] == [2, 3, 1]
    assert [row["queue_position"] for row in queue] == [1, 2, 3]


def test_queue_skips_resolved_and_cancelled(make_request: Any) -> None:
    requests = [
        make_request(id=1, status="RESOLVED"),
        make_request(id=2, status="CANCELLED"),
        make_request(id=3, status="OPEN"),
        make_request(id=4, status="IN_PROGRESS"),
    ]
    queue = prioritize_maintenance_queue(requests, NOW)
    assert sorted(row["id"] for row in queue) == [3, 4]


def test_queue_includes_in_progress(make_request: Any) -> None:
    queue = prioritize_maintenance_queue([make_request(id=1, status="IN_PROGRESS")], NOW)
    assert len(queue) == 1


def test_queue_empty(make_request: Any) -> None:
    assert prioritize_maintenance_queue([], NOW) == []
    assert prioritize_maintenance_queue([make_request(status="RESOLVED")], NOW) == []


def test_queue_tiebreak_by_created_at(make_request: Any) -> None:
    requests = [
        make_request(id=1, priority="CRITICAL", created_at=datetime(2026, 1, 14, 9, 0, 0)),
        make_request(id=2, priority="CRITICAL", created_at=datetime(2026, 1, 13, 21, 0, 0)),
    ]
    queue = prioritize_maintenance_queue(requests, NOW)
    assert [row["id"] for row in queue] == [2, 1]


def test_queue_tiebreak_by_id(make_request: Any) -> None:
    created = datetime(2026, 1, 14, 9, 0, 0)
    requests = [
        make_request(id=9, priority="HIGH", created_at=created),
        make_request(id=3, priority="HIGH", created_at=created),
    ]
    queue = prioritize_maintenance_queue(requests, NOW)
    assert [row["id"] for row in queue] == [3, 9]


def test_queue_escalated_high_precedes_critical(make_request: Any) -> None:
    requests = [
        make_request(id=1, priority="CRITICAL", created_at=datetime(2026, 1, 14, 9, 0, 0)),
        make_request(id=2, priority="HIGH", created_at=datetime(2026, 1, 13, 10, 0, 0)),
    ]
    queue = prioritize_maintenance_queue(requests, NOW)
    # Escalated HIGH has weight 1 and is older -> goes first.
    assert [row["id"] for row in queue] == [2, 1]
    assert queue[0]["effective_weight"] == 1
    assert queue[0]["escalated"] is True


def test_queue_does_not_mutate_input(make_request: Any) -> None:
    request = make_request(id=1)
    snapshot = dict(request)
    prioritize_maintenance_queue([request], NOW)
    assert request == snapshot
    assert "queue_position" not in request


def test_queue_result_is_copy(make_request: Any) -> None:
    request = make_request(id=1)
    queue = prioritize_maintenance_queue([request], NOW)
    assert queue[0] is not request


# --------------------------------------------------------------------------- #
# Incremental heap operations
# --------------------------------------------------------------------------- #
def test_push_request_into_heap(make_request: Any) -> None:
    heap = build_heap([make_request(id=1, priority="LOW")], NOW)
    push_request(heap, make_request(id=2, priority="CRITICAL"), NOW)
    assert len(heap) == 2
    assert heap[0][2] == 2  # critical popped first


def test_build_heap_orders_root_min(make_request: Any) -> None:
    heap = build_heap(
        [
            make_request(id=1, priority="LOW"),
            make_request(id=2, priority="MEDIUM"),
            make_request(id=3, priority="CRITICAL"),
        ],
        NOW,
    )
    assert heap[0][2] == 3


def test_build_heap_skips_inactive(make_request: Any) -> None:
    heap = build_heap(
        [make_request(id=1, status="RESOLVED"), make_request(id=2, status="OPEN")], NOW
    )
    assert len(heap) == 1
    assert heap[0][2] == 2
