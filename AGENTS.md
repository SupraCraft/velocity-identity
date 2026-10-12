# SupraCraft VIP public projection — cold-start instructions

This default `main` is only a bootstrap landing branch; it is NOT the qualified current code. For any implementation, discover the active branches/PRs and read their `AGENTS.md`, the exact code and build status. The owner of decisions and governed source is [the private VIP repository](https://github.com/SupraCraft/velocity-identity-private). Do not copy private secrets, credentials, experiments or deployment settings into this public source tree.

## Security and standards-first rule

**Do not build a second Minecraft authentication system.** VIP's qualified authentication paths are Minecraft ONLINE_SESSION, externally provisioned Ed25519 WORKLOAD and explicitly selected GUEST. Membership and authorization add scoped grants on top, rather than replace those mechanisms.

Before authoring identity, invitation, trust-domain, realm, signed-grant or delegated-agent mechanisms, consider Kerberos RFC 4120, SPIFFE identity/federation standards, OAuth RFC 8693 actor/delegation and RFC 9700 audience binding. Record **adopt/adapt/defer/reject**, what existing implementations cannot provide, a specific consuming requirement, owner and adversarial tests. Do not introduce a KDC, new IdP, client protocol, cross-realm federation or generic IAM as incidental scope expansion.

## Source and evidence recovery

- Governing [private enrollment #3](https://github.com/SupraCraft/velocity-identity-private/issues/3), [U2 authorization #5](https://github.com/SupraCraft/velocity-identity-private/issues/5), [multi-realm #6](https://github.com/SupraCraft/velocity-identity-private/issues/6).
- At 2026-10-12, public PRs [#3](https://github.com/SupraCraft/velocity-identity/pull/3), [#4](https://github.com/SupraCraft/velocity-identity/pull/4), [#5](https://github.com/SupraCraft/velocity-identity/pull/5), and [#6](https://github.com/SupraCraft/velocity-identity/pull/6) are independently build-tested, stacked, and unmerged. Recheck before continuing; no static listing remains authoritative.
- The private repo's `AGENTS.md`, `PROJECT_CONTRACT.json`, `governance/identity-primitive-gate.json` and current exact-head workset/PR are the first places to recover operating boundaries; when private access is absent, record UNKNOWN, don't invent decisions.
- A signed read-only READY plan and existing Velocity guest smoke do NOT prove member admission. Live activation requires externally pinned realm/audience, trustworthy monotonic rollback state, revocation and actual human/workload admission tests.
- No private Actions minutes, no physical TrueNAS APPLY, no unreviewed server/proxy credential mutations. Preserve observe -> plan -> apply -> verify and exact commit receipts.

When working on a current feature branch, follow that branch's fuller `AGENTS.md` and the owning private issue.
