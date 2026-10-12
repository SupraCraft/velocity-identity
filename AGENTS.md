# Public projection instructions

This repository is a public, buildable projection of SupraCraft Velocity Identity.

Treat repository state as a product/build surface, not as a place to introduce private deployment configuration, credentials, identity-provider secrets, private experiment evidence, or environment-specific state.

Changes and pull requests may be useful proposals and evidence. Maintainers reconcile accepted changes through the project authority before the next governed projection.

## Cold-start enrollment and identity security gate

Before proposing or implementing an invitation, identity, delegation, cryptographic trust, or cross-realm feature, read the authoritative private VIP issues [#3](https://github.com/SupraCraft/velocity-identity-private/issues/3), [#5](https://github.com/SupraCraft/velocity-identity-private/issues/5), [#6](https://github.com/SupraCraft/velocity-identity-private/issues/6) and the private standards-first security gate (`docs/identity-standards-and-primitive-gate-2026-10-12.md`, access permitting). If inaccessible, mark private decisions UNKNOWN and do not invent public replacement authority.

**Purpose:** Minecraft community membership/authorization; NOT a replacement authentication system. Before adding any security primitive, compare Kerberos realm/ticket semantics (RFC 4120), SPIFFE workload/trust-domain semantics, OAuth token-exchange actor/delegation (RFC 8693) and OAuth audience restrictions (RFC 9700), as applicable. Record `adopt|adapt|defer|reject` with a concrete consumer, existing implementation, threat/test and reason for non-reuse. Prefer existing qualified VIP `ONLINE_SESSION`, `WORKLOAD` and explicit `GUEST` paths. Do not add a KDC, new identity provider, general IAM service, cross-realm federation, or novel client protocol by analogy alone.

**Release guard:** per-install realm/audience binding, independently pinned signing keys, trusted rollback-resistant watermark, session revocation and real mixed-principal login qualification must precede enabling membership enforcement. Source-only plan `READY_FOR_RECONCILIATION` is **not** live VIP admission readiness. Do not weaken physical TrueNAS mutation HOLD.

Required engineering properties:
- deterministic observe/discover -> plan -> apply -> verify semantics;
- reproducible and repeatable builds/operations;
- idempotent apply;
- explicit rollback where the plugin owns reversible state;
- one semantic interface usable by humans, automation, and agents;
- unsupported runtime facts stay UNKNOWN rather than being guessed or recovered through unsupported reflection.

Do not introduce backend companion plugins, Velocity forks, custom proxy/backend protocols, dynamic OP synchronization, or a custom Yggdrasil service without an explicit project-scope change.


- Treat game UUID as identity, never as the sole connection/session key.
- New login remains closed until an effective policy has been independently verified.
- Native Velocity event listeners are not the provider arbitration mechanism; do not add authentication by racing peer listeners.
- After an authenticator claims a presented mechanism, its failure is terminal and must not fall through to guest or another provider.
- Preserve and reverify the Velocity-verified GameProfile for ONLINE_SESSION admission and reverify synthetic profiles before login completes.
- Gate external-host transfer separately from registered-backend switching.
- Paper 1.21.4 build 232 has qualified mixed ONLINE_SESSION and explicit synthetic identities on one unchanged modern-forwarding backend; other version/configuration combinations remain UNKNOWN until independently qualified.

Provider-family qualification must use real provider services instantiated in the test environment. Mocks, synthetic Yggdrasil/session servers, and fake provider implementations are not acceptance evidence.
