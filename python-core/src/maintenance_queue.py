"""Maintenance request priority queue (Algorithm 5).

Procedural implementation built on :mod:`heapq`. Effective weight combines the
base priority with SLA escalation:

    * ``CRITICAL`` = 1, ``HIGH`` = 2, ``MEDIUM`` = 3, ``LOW`` = 4;
    * ``MEDIUM`` waiting > 48h is escalated to weight 2;
    * ``HIGH`` waiting > 24h is escalated to weight 1.

Ties are resolved by ``created_at`` (earlier first) and then by ``id``.
"""

from __future__ import annotations

import heapq
from datetime import datetime
from typing import Any

PRIORITY_WEIGHTS: dict[str, int] = {
    "CRITICAL": 1,
    "HIGH": 2,
    "MEDIUM": 3,
    "LOW": 4,
}

#: (priority, hours threshold, escalated weight)
SLA_RULES: dict[str, tuple[int, int]] = {
    "MEDIUM": (48, 2),
    "HIGH": (24, 1),
}

ACTIVE_STATUSES = ("OPEN", "IN_PROGRESS")


def calculate_effective_priority_weight(
    priority: str,
    created_at: datetime,
    now: datetime,
) -> dict[str, Any]:
    """Compute the effective priority weight with SLA escalation.

    Args:
        priority: One of ``CRITICAL``/``HIGH``/``MEDIUM``/``LOW``.
        created_at: Timestamp the request was received.
        now: Reference timestamp for SLA age.

    Returns:
        Dict with ``base_weight``, ``effective_weight``, ``escalated`` and
        ``waiting_hours`` (float).
    """
    base_weight = PRIORITY_WEIGHTS[priority]
    waiting_hours = (now - created_at).total_seconds() / 3600.0

    effective = base_weight
    rule = SLA_RULES.get(priority)
    if rule is not None:
        threshold_hours, escalated_weight = rule
        # Escalate strictly after the SLA threshold is exceeded (hours > limit).
        if waiting_hours > threshold_hours:
            effective = escalated_weight

    return {
        "base_weight": base_weight,
        "effective_weight": effective,
        "escalated": effective < base_weight,
        "waiting_hours": waiting_hours,
    }


def _sort_key(request: dict[str, Any], now: datetime) -> tuple[int, datetime, int]:
    """Build the deterministic heap key for a request."""
    weight = calculate_effective_priority_weight(
        request["priority"], request["created_at"], now
    )["effective_weight"]
    return (weight, request["created_at"], request["id"])


def prioritize_maintenance_queue(
    requests: list[dict[str, Any]],
    now: datetime,
) -> list[dict[str, Any]]:
    """Order active maintenance requests for servicing.

    Only ``OPEN`` and ``IN_PROGRESS`` requests participate. The result is
    produced by pushing every active request into a binary heap and popping it
    back, which is what the production service does.

    Args:
        requests: Normalized maintenance request list.
        now: Reference timestamp for SLA escalation.

    Returns:
        Ordered list of dicts with queue fields ``queue_position``,
        ``effective_weight`` and ``escalated`` merged into a copy of each
        request.
    """
    heap: list[tuple[int, datetime, int, dict[str, Any]]] = []
    for request in requests:
        if request["status"] not in ACTIVE_STATUSES:
            continue
        key = _sort_key(request, now)
        heapq.heappush(heap, (key[0], key[1], key[2], request))

    queue: list[dict[str, Any]] = []
    position = 1
    while heap:
        _, _, _, request = heapq.heappop(heap)
        weight_info = calculate_effective_priority_weight(
            request["priority"], request["created_at"], now
        )
        ordered = dict(request)
        ordered["queue_position"] = position
        ordered["effective_weight"] = weight_info["effective_weight"]
        ordered["escalated"] = weight_info["escalated"]
        queue.append(ordered)
        position += 1
    return queue


def push_request(
    heap: list[tuple[int, datetime, int, dict[str, Any]]],
    request: dict[str, Any],
    now: datetime,
) -> list[tuple[int, datetime, int, dict[str, Any]]]:
    """Add a single request to an existing heap (``O(log Q)``).

    Args:
        heap: Heap produced by :func:`prioritize_maintenance_queue`-compatible
            tuples (a list of ``(weight, created_at, id, request)``).
        request: Request to add.
        now: Reference timestamp for SLA escalation.

    Returns:
        The same list object, mutated as a valid heap.
    """
    key = _sort_key(request, now)
    heapq.heappush(heap, (key[0], key[1], key[2], request))
    return heap


def build_heap(
    requests: list[dict[str, Any]],
    now: datetime,
) -> list[tuple[int, datetime, int, dict[str, Any]]]:
    """Build a raw heap of active requests (``heapq.heapify``, ``O(Q)``).

    Args:
        requests: Normalized maintenance request list.
        now: Reference timestamp for SLA escalation.

    Returns:
        List laid out as a valid min-heap.
    """
    heap: list[tuple[int, datetime, int, dict[str, Any]]] = []
    for request in requests:
        if request["status"] not in ACTIVE_STATUSES:
            continue
        key = _sort_key(request, now)
        heap.append((key[0], key[1], key[2], request))
    heapq.heapify(heap)
    return heap
