# VIP U2b.2 — bounded membership activation model and qualification gate

State: **SOURCE-ONLY MODEL / NOT LIVE**. Authority: `SupraCraft/velocity-identity-private#3/#5/#6`. This document may be public-projected because it contains no deployment secret, credential, principal or real membership record.

## Exact source starting point

- Private development PR #7: `58b179a045616d757de484d14cd79dd9fbcf2407` (observed 2026-10-25).
- Public sanitized U2c PR #6: `565aabf7b4604900c48a67263519146f29895d01` (observed 2026-10-25).
- Existing U1/U2a/U2b.1/U2c source CI passes; none has demonstrated **live membership enforcement**.
- `AuthorityGate.acceptsLogins()` currently accepts `READY || DEGRADED`. `ReconciliationService.restoreLastKnownGood()` can restore a prior `PolicyRuntime` policy. Those are not sufficient proof for revocable membership; preserve legacy behavior only when membership enforcement is **disabled**.

## Correct terminology and authority

A VIP `MembershipAdmissionPolicy.Grant.servers` element is a **Velocity registered backend name**; it is not a vanilla/Paper dimension, region, arbitrary in-game world or OP permission. A world deployment may map one-to-one to a backend, but the consumer must explicitly prove that mapping. VIP controls entry/transfer to a backend. Gameplay controls belong to the backend/game authority. Do not add a backend companion, rewrite Velocity, or invent a second authentication path.

## Standards-first primitive decisions

| Primitive | Prior art | Decision | Consumer / why | Falsification |
| --- | --- | --- | --- | --- |
| Verified human/workload principal | Velocity online session / existing VIP Ed25519 | **KEEP** | Already live qualified; avoid password/provider duplication | Real session and workload negative paths must still pass |
| Realm/audience scope | Kerberos RFC 4120; SPIFFE trust-domain semantics; OAuth RFC 9700 audience | **ADAPT** | Existing U2c V2 envelope must match separately pinned installing realm | Policy for realm A never authorizes realm B |
| Version/freshness/revocation | TUF expiry/rollback semantics; Zanzibar causal consistency | **ADAPT TEST ORACLES** | U2b.1 is read-only and in-memory cursor is not durable | Full restore, partition, stale revision, equal-revision equivocation |
| Monotonic witness service | GitHub protected ref or independent store | **DEFER PRODUCTION ADOPTION** until independent trust proof | GitHub admin/bypass, credential, CAS, outage and recovery assumptions remain unqualified | Admin rollback, force update, concurrent writers, partition |
| New KDC / IdP / shared SQL authorization server | Kerberos/SPIFFE/OPA/SpiceDB deployment products | **REJECT** for current scope | Existing auth works; finite backend grant policy earns no new online service | Any new service requires a named extra consumer and gate |
| Autonomous dispatcher | Agent Dispatch existing model | **REUSE** only when VIP-specific delegation admitted | Existing proof workset grants no VIP source/dispatch authority | Denied or missing delegation cannot mutate source |

## Membership readiness contract for a future Java implementation

`LegacyAuthReadiness` and `MembershipAdmissionReadiness` must be separate. When membership is disabled, previous admission semantics remain unchanged. When enabled, no known stale/expired/missing/untrusted membership can be treated as the existing general `DEGRADED` state permitting login. A successful Ed25519 signature does **not** prove authorization freshness.

A revocation acknowledgement has to be defined precisely: in the stringent model, it is a **committed external witness revision**. Under that contract, each new login and backend transfer needs independently authenticated current witness evidence; read failure means **deny**, not cached allow. Reconciliation compares `(installation realm, audience, signer fingerprint, revision, digest)` against independently pinned trust. A crash after witnessed revision advance but before local publication denies until the exact witnessed revision is recovered and verified. Old local policy rollback cannot regrant. Current connected sessions require a separately qualified eviction path; this model does not establish a bound on eviction latency.

**Alternative bounded-stale lease** is a distinct, weaker contract: admissions can continue for a declared short interval while disconnected, but instantaneous post-ack revocation cannot be claimed. Do not combine the strong witness-per-decision and lease modes into one passing claim. This trade-off must be explicitly tested and documented before production acceptance.

## Executable cheap falsifier

```sh
python3 scripts/qualification/vip_membership_state_explorer.py --json
```

The deterministic standard-library-only reference explores every reachable state in a finite two-revision system: an initial allowed revision and an independently acknowledged revoking revision. It exercises independent witness availability, clock trust, local restart/restore, initial admission, transfer, and local activation. It separately runs an **intentionally defective cached-policy negative control**, and records the shortest revocation-then-admission counterexample. The corrected reference predicate is checked against all enumerated states.

**What this proves:** the model's own invariants, under explicitly assumed synchronous, authoritative witness-read semantics. **What it does not prove:** that GitHub or any future witness actually supplies them; thread/event fairness, immediate session eviction, Java implementation equivalence, real identity authentication, signature trust, proxy network security, or Minecraft 26.3 integration.

## Interleaved prepare / commit negative control

The model also explores a prepared admission that is not yet committed when the independent witness acknowledges a revocation. The intentionally unsafe deferred commit produces `prepare_admission -> acknowledge_revoke -> unsafe_commit_pending_without_revalidate`. The corrected **modeled atomic commit** requires another current-witness check and rejects the stale prepared generation. This result **does not prove** that a real Velocity event handler can atomically coordinate a remote witness read with a Minecraft connection being established; a production design needs an explicit linearization and in-flight cancellation contract. Do not claim instant global revocation across disconnected proxies or immediate eviction of already-connected clients.

## Dependency-ordered follow-on engineering gates

1. **Witness feasibility** — contrast trusted remote linearizable compare/read and bounded-time leases using disposable, synthetic state. Require independent trust ownership, monotonic signer/revision/digest, race, administrative rollback, clock, partition and restore evidence. If unavailable, mark production authorization **BLOCKED**, keep opt-in enforcement disabled and do not promote.
2. **Atomic Java implementation** — implement separate opt-in membership gate and generation, immutable published effective policy, stale-observation denial, and no revoked-grant restoration on failure. Consume U2c verified realms and a protected cursor; keep private `product/` as authority.
3. **Live validation** — reuse `SupraCraft/mc-server-images#72` accepted public CI for actual Velocity→Paper 26.3 and Velocity→VanillaCord→vanilla 26.3 negative/positive forwarding. Add membership-controlled ONLINE_SESSION and WORKLOAD; existing forwarding evidence is *not* VIP membership evidence. Test connected-session revocation, transfer races and direct backend ingress.
4. **Three audiences** — human readable denial/recovery, automation idempotent machine receipt, agent bounded delegation; all operate the same underlying grant evaluator, never a separate rule engine.
5. **Source custody** — privately review/sanitize each batch before deliberate public projection, pin exact heads and independent test receipts in private DLE. No private Actions minutes, secrets in public CI, source auto-merge, production deployment, or physical TrueNAS APPLY.

**Promotion decision for this document: SOURCE_ONLY.** The actual Java/U2b.2 integration, witness adoption and LIVE admission remain unqualified pending real evidence.
