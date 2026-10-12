# U2b.1 — Read-only signed membership plan/observe (source-only)

Authority: [SupraCraft/velocity-identity-private#5](https://github.com/SupraCraft/velocity-identity-private/issues/5). Predecessor U2a: [public VIP #4](https://github.com/SupraCraft/velocity-identity/pull/4). This increment is a zero-mutation preflight **not** an enrollment or runtime admission feature.

## Preflight inputs and outcomes

- Signed canonical `VIP-MEMBERSHIP/1` projection, detached Ed25519 signature, independently pinned DER public key.
- Externally authenticated `SignedMembershipProjection.Watermark` (revision, digest, signer fingerprint) obtained from a *protected, rollback-resistant* authority. This work does not construct, trust, write or persist such an authority.
- Explicit instant for exact deterministic behavior.
- `MembershipProjectionPreflight.observe` computes an independent digest of input bytes plus trust cursor. `plan` validates signature, bounded validity, canonical principal/world representation and version/digest/signing-key fence. It returns a stable plan fingerprint, status and blocker.
- `MembershipProjectionFilePreflight.inspect` bounds inputs, rejects symlinks, uses two separate disk reads, and provides two independently produced plans and a second observation. It writes no runtime state, identities, session records or file contents.

## What READY means

`READY_FOR_RECONCILIATION` means a **candidate** is valid *relative to caller-supplied trust inputs*. It is **not** VIP `AuthorityGate.READY`, not a runtime apply grant, and not sufficient evidence for live Minecraft admission. Neither preflight class is wired to Velocity events or VIP policy storage. Calling `plan` does not advance the trusted high-watermark, make a signed policy effective or revoke sessions.

Input files can still change between reads or after the second read; U2b.2 must use sealed handles or a single immutable content-addressed candidate when applying changes. A file cursor collocated with an untrusted snapshot cannot establish anti-rollback protection, especially after a full-disk restore. Thus even two identical source plans are **not** sufficient for production activation.

## U2b.2 gate before ANY live authorization

1. Specify external trust root and persisted monotonic cursor, initial enrollment/bootstrap and authorized signer rotation; demonstrate restart/crash/full-filesystem-restore fail closed.
2. Bind the *same immutable verified candidate* through VIP's observe→plan→apply→verify, including workload trust, host/profile policy, fingerprint readback, last-known-good behavior and monotonic revocation semantics.
3. Gate activation behind explicit policy mode and safe boot. Disable mode preserves existing login behavior, but must not be represented as enrollment enforcement.
4. Authorize only verified canonical issuer + subject + kind; evaluate existing host/profile gate AND membership at login, preconnect, postconnect, and transfers. No automatic GUEST fallback. Re-evaluate connected sessions and enforce expiration/partition bounds.
5. Real disposable ONLINE_SESSION and WORKLOAD login via stock Velocity and unchanged VanillaCord/Paper separately, including allow/deny world transitions, revocation and rollback negatives.
6. Public/free CI, exact-head receipts, independent validator, no physical TrueNAS APPLY.

## Source acceptance and experiment

Run Gradle JUnit on exact PR HEAD through public GHA. `MembershipProjectionPreflightTest` demonstrates two byte-identical plans on same inputs, independent observation, zero mutation of candidate/signature, rollback/equivocation/wrong signer/expiry blocking. `MembershipProjectionFilePreflightTest` exercises actual filesystem handles and inspection without modifying inputs, including oversized/symlink negatives.

Do not infer these tests establish an independent *trusted watermark*. The unit-test watermark is a controlled signed fixture used to falsify pure logic, not an attestation of a production external authority.
