# U2c realm-bound signed membership proof (SOURCE ONLY)

Authority: [private VIP #6](https://github.com/SupraCraft/velocity-identity-private/issues/6), [U2 #5](https://github.com/SupraCraft/velocity-identity-private/issues/5). Prior-art gate: [RFC 4120 Kerberos](https://www.rfc-editor.org/rfc/rfc4120) realm/service ticket and [SPIFFE trust domain](https://spiffe.io/docs/latest/spiffe-specs/spiffe-id/) namespace, [SPIFFE federation](https://spiffe.io/docs/latest/spiffe-specs/spiffe_federation/) independently associated trust bundles, [RFC 9700 OAuth BCP](https://www.rfc-editor.org/rfc/rfc9700) audience restrictions.

## Primitive decision — ADAPT, not a new authentication service
- **Property:** the same signed world-grant policy cannot authorize another independently operated realm even if hostname, principal/UUID, world names, or signing key are accidentally shared.
- **Existing implementation:** retain `VIP-MEMBERSHIP/1` canonical policy data and the Ed25519 detached signature approach. Human ONLINE_SESSION, WORKLOAD Ed25519 login, and explicit GUEST remain untouched.
- **Minimal change:** sign a V2 domain-separated context consisting of the canonical ASCII prefix below **followed byte-for-byte by the V1 policy content**:

```text
VIP-MEMBERSHIP-REALM/2
REALM<tab><pinned issuing UUID>
AUDIENCE<tab><pinned receiving UUID>
<V1 canonical membership bytes>
```

- The above `<tab>` markers are ASCII TAB, each header line ends with LF; UUIDs are canonical lowercase. Both IDs are chosen from independently pinned deployment inputs, NEVER from the untrusted policy or invitation itself. Ed25519 signs exactly those bytes; no signature is accepted under another realm context.
- `verifyForRealm` verifies the signature and parses the already-qualified V1 canonical grant serializer; its success does not grant login. `RealmVerified` cannot be publicly constructed; `RealmWatermark` composes the previously defined revision/signer fence while binding realm + audience.
- **Why not adopt full Kerberos/SPIFFE/OAuth infrastructure?** VIP already authenticates Minecraft sessions and workloads; installing their issuers, KDC, OAuth IdP, or a federated identity service adds an additional critical path without a current consuming requirement.
- **Alternative rejected:** accepting V1-only signed membership with a common signing key for multiple installations. That cannot prove the intended recipient.

## Compatibility / security
- V1 signed policy is intentionally **not** valid as V2. A future live membership-enforcement configuration must require V2 explicitly and reject silent fallback. Keep the original V1 source API only for existing source tests/migration; do not activate it as deployment authorization.
- No client changes or additional provider protocol; server-side issuance signs V2 context. Distinct workload keys and trust exports per realm still required because WORKLOAD login **V1** does not include a realm-bound challenge. Do not conflate the two.
- Initial realm ID/key pin and rollback-resistant cursor are still external authority prerequisites. The currently pure `RealmWatermark` cannot prevent rollback after a full-disk restore by itself.
- Operator/domain identity verification at first contact is not established by a randomly generated realm UUID. HTTPS protects the web invitation surface, not vanilla client-to-game mutual attestation.
- A valid grant is not OP, block access, or authorization for an agent's external tools.

## Tests / HOLD
- Exact same canonical policy bytes, same signing key, same name and same UUID fail in a differently pinned realm/audience.
- Wrong audience under same realm fails; V1 signature cannot be substituted for V2; reverse substitution fails.
- A realm-typed watermark rejects cross-realm reuse and same-revision equivocation; expired/tampered grants deny.
- This PR modifies only the source proof code/tests/docs and does NOT wire a runtime policy loader, reconcile grant apply/rollback, persist a monotonic cursor, or run new live member admission. Continue U2b.2 after reviewed and independently qualified, with physical TrueNAS APPLY on HOLD.
