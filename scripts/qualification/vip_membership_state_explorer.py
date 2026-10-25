#!/usr/bin/env python3
"""Dependency-free bounded falsifier for VIP membership activation ordering.

This is an abstract concurrent-state model, NOT a trusted witness,
production membership evaluator, Velocity emulator, or live qualification.
It intentionally demonstrates why local cached authorization is insufficient.
"""

from __future__ import annotations

import argparse
from collections import deque
from dataclasses import dataclass, replace
import hashlib
import json


@dataclass(frozen=True)
class State:
    # W1 grants entry; W2 is an acknowledged revocation. Witness is external.
    witness_revision: int = 1
    local_revision: int = 1
    readiness: str = "READY"
    witness_reachable: bool = True
    clock_trusted: bool = True
    connected_revision: int = 0  # 0 means no connected player
    pending_revision: int = 0  # authorization prepared but not committed


def external_fresh(state: State) -> bool:
    """An authoritative read on EACH admission/transfer, never cached.

    This assumption is intentionally stronger than a bounded stale lease:
    no witness connectivity or uncertain clock means denial. An implementation
    must independently prove external witness freshness and authority.
    """
    return (state.witness_reachable and state.clock_trusted
            and state.readiness == "READY"
            and state.local_revision == state.witness_revision)


def admit(state: State) -> bool:
    return external_fresh(state) and state.local_revision == 1


def transfer(state: State) -> bool:
    return (state.connected_revision != 0 and external_fresh(state)
            and state.local_revision == 1
            and state.connected_revision == state.local_revision)


def unsafe_cached_admit(state: State) -> bool:
    """Intentionally defective oracle: old local grant without fresh witness."""
    return state.readiness == "READY" and state.local_revision == 1


def transitions(state: State) -> tuple[tuple[str, State], ...]:
    candidates = (
        ("acknowledge_revoke", replace(state, witness_revision=2)),
        ("restore_old_local_image", replace(state, local_revision=1, readiness="READY")),
        ("witness_partition", replace(state, witness_reachable=False)),
        ("witness_reconnect", replace(state, witness_reachable=True)),
        ("clock_uncertain", replace(state, clock_trusted=False)),
        ("clock_recovered", replace(state, clock_trusted=True)),
        ("begin_reconcile", replace(state, readiness="BLOCKED")),
        ("activate_witnessed_revision", replace(state,
            local_revision=state.witness_revision, readiness="READY")
            if state.witness_reachable and state.clock_trusted else state),
        ("admit_player", replace(state, connected_revision=state.local_revision)
            if admit(state) else state),
        ("prepare_admission", replace(state, pending_revision=state.local_revision)
            if admit(state) else state),
        ("commit_pending_with_fresh_witness", replace(state,
            connected_revision=state.pending_revision, pending_revision=0)
            if state.pending_revision and state.pending_revision == state.local_revision
            and admit(state) else state),
        ("discard_pending", replace(state, pending_revision=0)),
        ("disconnect_player", replace(state, connected_revision=0)),
    )
    return tuple((name, next_state) for name, next_state in candidates
                 if next_state != state)


def explore(max_states: int = 100_000) -> dict:
    """Enumerate all reachable abstract states; search shortest bad trace.

    The counterexample is for the *unsafe reference predicate*, while the
    proposed predicate must never allow a new admission or transfer after
    acknowledged revocation, or during a partition / untrusted clock.
    """
    initial = State()
    pending = deque([(initial, ())])
    visited = {initial}
    edge_count = 0
    unsafe_trace = None
    pending_race_trace = None
    admits = 0
    transfers = 0
    while pending:
        state, trace = pending.popleft()
        if admit(state):
            admits += 1
        if transfer(state):
            transfers += 1
        if state.witness_revision == 2:
            assert not admit(state), "post-ack new admission"
            assert not transfer(state), "post-ack backend transfer"
            if unsafe_trace is None and unsafe_cached_admit(state):
                unsafe_trace = (*trace, "unsafe_cached_admit_after_ack")
            if pending_race_trace is None and state.pending_revision == 1:
                pending_race_trace = (*trace, "unsafe_commit_pending_without_revalidate")
        if not state.witness_reachable or not state.clock_trusted:
            assert not admit(state) and not transfer(state), "offline/clock uncertainty"
        if state.local_revision != state.witness_revision:
            assert not admit(state) and not transfer(state), "stale policy generation"
        for label, successor in transitions(state):
            edge_count += 1
            if successor not in visited:
                visited.add(successor)
                if len(visited) > max_states:
                    raise RuntimeError("abstract state bound exceeded")
                pending.append((successor, (*trace, label)))
    assert unsafe_trace, "negative-control regression: missing known cached-policy defect"
    assert pending_race_trace, "negative-control regression: missing admission race"
    assert admits > 0, "missing positive new-admission state"
    assert transfers > 0, "missing positive transfer state"
    return {
        "schema": "vip-state-exploration/1",
        "qualification": "ABSTRACT_MODEL_ONLY_NOT_LIVE",
        "invariants": {
            "deny_new_after_revocation_ack": "PASS",
            "deny_transfer_after_revocation_ack": "PASS",
            "deny_without_authoritative_witness": "PASS",
            "deny_with_untrusted_clock": "PASS",
            "deny_stale_local_generation": "PASS",
            "positive_admission_reachable": "PASS",
            "positive_transfer_reachable": "PASS",
            "cached-policy_negative_control": "COUNTEREXAMPLE_FOUND",
            "prepare_commit_race_negative_control": "COUNTEREXAMPLE_FOUND",
        },
        "reachable_states": len(visited),
        "explored_edges": edge_count,
        "unsafe_counterexample": list(unsafe_trace),
        "pending_admission_counterexample": list(pending_race_trace),
        "assumptions": [
            "witness is a separate linearizable authority with trustworthy revisions",
            "every admission and transfer obtains a current witness revision",
            "loss of witness or trustworthy time denies new admission and transfer",
            "revocation acknowledgment is linearized by the witness",
            "modeled admission/commit transitions are atomic; real event races remain unproven",
            "connected sessions require separate bounded eviction qualification",
            "full-disk rollback resistance, trust-root custody and live Velocity are NOT proven",
        ],
    }


def run_tests() -> None:
    a = State()
    assert admit(a)
    assert not transfer(a)
    connected = replace(a, connected_revision=1)
    assert transfer(connected)
    revoked = replace(connected, witness_revision=2)
    assert not admit(revoked)
    assert not transfer(revoked)
    assert unsafe_cached_admit(revoked)
    prepared = replace(a, pending_revision=1)
    assert any(label == "commit_pending_with_fresh_witness" for label, _ in transitions(prepared))
    revoked_pending = replace(prepared, witness_revision=2)
    assert not any(label == "commit_pending_with_fresh_witness" for label, _ in transitions(revoked_pending))
    assert not admit(replace(a, witness_reachable=False))
    assert not admit(replace(a, clock_trusted=False))
    assert not admit(replace(a, local_revision=2))
    assert not admit(replace(a, readiness="BLOCKED"))
    assert not admit(replace(revoked, local_revision=2))
    assert not admit(replace(revoked, witness_reachable=False))
    assert len({s for _, s in transitions(a)}) >= 3


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="emit canonical JSON receipt")
    args = parser.parse_args()
    run_tests()
    result = explore()
    receipt = json.dumps(result, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    if args.json:
        print(receipt)
    else:
        print(json.dumps(result, indent=2, sort_keys=True))
    print("VIP_ABSTRACT_MODEL_PASS", "sha256=" + hashlib.sha256(receipt.encode()).hexdigest(),
          file=__import__("sys").stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
