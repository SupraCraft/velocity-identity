# U2a: Signed membership projection — source-only qualification

Authority: [private VIP #5](https://github.com/SupraCraft/velocity-identity-private/issues/5). Depends on [public VIP #3](https://github.com/SupraCraft/velocity-identity/pull/3) U1 pure evaluator.

## Contract
`SignedMembershipProjection` provides a bounded deterministic UTF-8 canonical payload, Ed25519 detached signature verification against a public key independently pinned by the operator/deployment authority, and a pure revision/equivocation fence.

Canonical format:
```text
VIP-MEMBERSHIP/1
REV<TAB>positive-decimal-revision
VALID<TAB>validFrom-ISO-Instant<TAB>expiresAt-ISO-Instant
G<TAB>base64url-issuer<TAB>base64url-subject<TAB>HUMAN|WORKLOAD<TAB>sorted-comma-separated-server-list<TAB>from<TAB>until
R<TAB>base64url-issuer<TAB>base64url-subject<TAB>HUMAN|WORKLOAD
```

All lines are LF-terminated; G and R records are individually sorted; no wildcard backend grant; no duplicate principal; no grant outside the snapshot envelope. Exact canonical bytes are signed, not parsed or reserialized JSON. Inputs are bounded to 256 KiB/2000 records. Invalid encoding, scope, time, signature, issuer binding or out-of-window payload fails closed.

**The verifier does not create or receive signing private keys.** Credential issuance belongs to the separately governed enrollment authority; deployment pins trusted verifier key material outside this artifact.

## This is not yet a runtime gate
- There is no membership policy loader, live VIP routing hook, credential issuer, active-session enforcement or published deployment schema here.
- `Watermark.advance` is pure and requires a *durable authenticated* high-watermark supplied by a future caller. Its return value is not persisted. It is not sufficient by itself to stop rollback after full host/disk restoration.
- The existing VIP `AdmissionPolicy` and `AuthorityGate` are unaffected.
- U2b must implement credential-pinned bootstrapping, trusted durable high-watermark storage, policy reconciliation, rollback *without revoked-access resurrection*, and active-session invalidation. Fail closed if trusted monotonic state cannot be recovered. Multi-host coordination and revocation propagation require separately proved bounds.
- A signature and valid admission grant never imply OP, in-game building privilege, or agent runtime tool authorization.
- The current online-session issuer is the Velocity-configured trust domain. A federation aggregator must not be misrepresented as an independently verified upstream issuer.
- U2a Java test fixtures sign synthetic test policy bytes for cryptographic negative testing. These are **not** fake Minecraft identity provider acceptance tests.

## Verification
Public Java 25 / Velocity 4.2.0 build+JUnit baseline plus Velocity 4.2.1-SNAPSHOT advisory. Exact PR head and raw job evidence are required. Existing live guest smoke proves only no regression in existing routes; separate U2b and U3+ tests must prove new mixed-principal admission.

No merge or production deployment claim on this source-only feature without U2b and independent live qualification.
