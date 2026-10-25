#!/usr/bin/env python3
"""VIP U2b.2 bounded witness-chain falsifier (STANDARD LIBRARY ONLY).

No actual witness, trusted current head, signer, protected repository rule or
production membership admission is instantiated. This is a deterministic
consistency oracle plus a negative demonstration of GitHub Contents blob-SHA
CAS and ABA rollback. Never use this fixture as deployment trust.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
import argparse
import hashlib
import json
import sys

ZERO = "0" * 64
REALM = "11111111-1111-4111-8111-111111111111"
AUDIENCE = "11111111-1111-4111-8111-111111111111"
SIGNER = "a" * 64


class Rejected(ValueError):
    """A candidate is not qualified for the caller's external trust claim."""


def hex_digest(value: str) -> str:
    if (len(value) != 64 or value.lower() != value
            or any(char not in "0123456789abcdef" for char in value)):
        raise Rejected("noncanonical digest")
    return value


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


@dataclass(frozen=True)
class Entry:
    realm: str
    audience: str
    signer: str
    revision: int
    policy_digest: str
    parent_hash: str

    def hash(self) -> str:
        fields = (self.realm, self.audience, hex_digest(self.signer),
                  str(self.revision), hex_digest(self.policy_digest),
                  hex_digest(self.parent_hash))
        canonical = "VIP-WITNESS-CHAIN/1\n" + "\n".join(fields) + "\n"
        return sha256(canonical.encode("utf-8"))


@dataclass(frozen=True)
class TrustedFloor:
    """A floor that MUST come from outside the rollbackable candidate log."""
    realm: str
    audience: str
    signer: str
    revision: int
    head_hash: str


def verify_chain(
        entries: tuple[Entry, ...],
        external_floor: TrustedFloor | None,
        externally_verified_current_head: str | None) -> str:
    """Check a full chain against independently supplied floor and current head.

    Both external inputs are plain values HERE; the procedure cannot establish
    their authenticity. The future production adapter must prove that separately.
    """
    if external_floor is None or externally_verified_current_head is None:
        raise Rejected("no independently verified floor and current head")
    if not (0 < len(entries) <= 1000):
        raise Rejected("missing or oversized witness history")
    hex_digest(external_floor.signer)
    hex_digest(external_floor.head_hash)
    hex_digest(externally_verified_current_head)
    if external_floor.revision < 1:
        raise Rejected("invalid trusted floor")
    previous_head = ZERO
    for index, entry in enumerate(entries, start=1):
        if (entry.realm != external_floor.realm
                or entry.audience != external_floor.audience
                or entry.signer != external_floor.signer):
            raise Rejected("untrusted realm, audience or signing-key identity")
        if entry.revision != index:
            raise Rejected("skipped, duplicated or non-monotonic revision")
        if entry.parent_hash != previous_head:
            raise Rejected("broken append-only parent linkage")
        previous_head = entry.hash()
        if index == external_floor.revision:
            if previous_head != external_floor.head_hash:
                raise Rejected("history conflicts with independently pinned floor")
    if len(entries) < external_floor.revision:
        raise Rejected("candidate rolled back below trusted floor")
    if previous_head != externally_verified_current_head:
        raise Rejected("candidate is not the independently verified current head")
    return previous_head


def synthetic_entries() -> tuple[Entry, Entry, Entry]:
    first = Entry(REALM, AUDIENCE, SIGNER, 1, "1" * 64, ZERO)
    second = Entry(REALM, AUDIENCE, SIGNER, 2, "2" * 64, first.hash())
    third = Entry(REALM, AUDIENCE, SIGNER, 3, "3" * 64, second.hash())
    return first, second, third


def weak_blob_cas_exposes_aba() -> tuple[bool, bool, bool]:
    """Equivalent to a contents-file expected-blob check, NOT a monotonic log."""
    state = json.dumps({"revision": 1, "test_only": True}, sort_keys=True)
    first = sha256(state.encode())
    stale_expected = first

    def attempt(expected: str, next_blob: str) -> bool:
        nonlocal state
        if sha256(state.encode()) != expected:
            return False
        state = next_blob
        return True

    to_two = attempt(first, json.dumps(
        {"revision": 2, "test_only": True}, sort_keys=True))
    stale_rejected = not attempt(stale_expected, json.dumps(
        {"revision": 3, "test_only": True}, sort_keys=True))
    current_sha = sha256(state.encode())
    rolled_back = attempt(current_sha, json.dumps(
        {"revision": 1, "test_only": True}, sort_keys=True))
    aba_accepted = attempt(stale_expected, json.dumps(
        {"revision": 3, "test_only": True}, sort_keys=True))
    return to_two and stale_rejected, rolled_back, aba_accepted


def run_proofs() -> dict:
    one, two, three = synthetic_entries()
    floor_one = TrustedFloor(REALM, AUDIENCE, SIGNER, 1, one.hash())
    floor_two = TrustedFloor(REALM, AUDIENCE, SIGNER, 2, two.hash())
    cases: dict[str, str] = {}

    def accepts(name: str, entries: tuple[Entry, ...],
                floor: TrustedFloor | None,
                latest: str | None) -> None:
        assert verify_chain(entries, floor, latest) == entries[-1].hash()
        cases[name] = "PASS"

    def rejects(name: str, entries: tuple[Entry, ...],
                floor: TrustedFloor | None,
                latest: str | None) -> None:
        try:
            verify_chain(entries, floor, latest)
        except Rejected:
            cases[name] = "EXPECTED_REJECTION"
        else:
            raise AssertionError("unsafe witness accepted: " + name)

    accepts("valid_append_chain", (one, two, three), floor_one, three.hash())
    accepts("valid_idempotent_current_head", (one, two), floor_two, two.hash())
    rejects("missing_external_floor", (one, two), None, two.hash())
    rejects("missing_verified_current_head", (one, two), floor_one, None)
    rejects("rollback_below_trusted_floor", (one,), floor_two, one.hash())
    rejects("rollback_with_old_pinned_floor", (one,), floor_one, two.hash())
    rejects("alternate_same_revision", (one, replace(
        two, policy_digest="f" * 64)), floor_two, two.hash())
    rejects("wrong_realm", (one, replace(two, realm="different")), floor_one, two.hash())
    rejects("wrong_audience", (one, replace(two, audience="different")),
            floor_one, two.hash())
    rejects("signer_swap", (one, replace(two, signer="b" * 64)),
            floor_one, two.hash())
    rejects("broken_parent", (one, replace(two, parent_hash=ZERO)),
            floor_one, two.hash())
    rejects("duplicate_revision", (one, replace(two, revision=1)),
            floor_one, two.hash())
    rejects("skipped_revision", (one, replace(two, revision=3)),
            floor_one, two.hash())
    rejects("malformed_digest", (one, replace(two, policy_digest="xyz")),
            floor_one, two.hash())
    rejects("untrusted_latest_head", (one, two), floor_one, three.hash())
    advance, rollback, aba = weak_blob_cas_exposes_aba()
    assert advance and rollback and aba
    cases["weak_blob_cas_rejects_stale_write"] = "PASS"
    cases["weak_blob_cas_permits_rollback"] = "COUNTEREXAMPLE"
    cases["weak_blob_cas_aba_reuses_original_sha"] = "COUNTEREXAMPLE"
    return {
        "schema": "vip-witness-qualification/1",
        "status": "SOURCE_ONLY_ORACLE_NOT_PRODUCTION_WITNESS",
        "cases": cases,
        "case_count": len(cases),
        "github_api_observation": "private DLE #5 stores live synthetic API evidence",
        "blocking_assumptions": [
            "independent authentication and antirollback custody of floor",
            "current head is served by a trustworthy sufficiently consistent authority",
            "admin bypass, key rotation, fork and full-disk restore outside probe scope",
            "chain hash is integrity chaining, not issuer signature or trusted time",
            "a valid chain prefix can be replayed if the independent latest head rolls back",
            "no live Minecraft/Velocity membership admission is demonstrated",
        ],
        "next_gate": "compare true remote head CAS, protection and trusted recovery; HOLD live APPLY",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    receipt = run_proofs()
    canonical = json.dumps(receipt, sort_keys=True, separators=(",", ":"))
    print(canonical if args.json else json.dumps(receipt, indent=2, sort_keys=True))
    print("VIP_WITNESS_CHAIN_ORACLE_PASS sha256=" + sha256(canonical.encode()),
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
