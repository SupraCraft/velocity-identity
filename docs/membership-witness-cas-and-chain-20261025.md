# VIP U2b.2 — GitHub Contents witness falsification and candidate-chain oracle

**Status:** EXPERIMENTAL SOURCE QUALIFICATION ONLY. **Production trusted-witness gate: BLOCKED.**  
Authority: `SupraCraft/velocity-identity-private#5`; exact prior SOURCE-only private PR #9/public PR #8.  
Date: 2026-10-25. Public experiment uses only synthetic, published data.

## Actual external API experiment: Rep-A

Experimental public repo branch (NOT a witness authority):
`SupraCraft/velocity-identity@experiment/vip-u2b2-witness-contents-cas-20261025:testdata/qualification/witness-contents-probe.json`

| Step | Input expected file blob SHA | Actual outcome |
| --- | --- | --- |
| Seed synthetic revision 1 | new file | ACCEPT, commit `95b3972a73db1c7a8e322ccbf51d244ec35dac17`, blob `23033e7aaf8faa662ace313032c801e83c4ab076` |
| Advance 1 → 2 | `23033e7aaf8faa662ace313032c801e83c4ab076` | ACCEPT, commit `487f0a3bd093c8e88fb5262b58d4161fa339034d`, blob `3ff26791cb9cf0e00963dc997b5299324c1b58cf` |
| Stale competing revision 3 | stale original `23033e7aaf8faa662ace313032c801e83c4ab076` | HTTP **409** conflict |
| Rollback to revision 1 | `3ff26791cb9cf0e00963dc997b5299324c1b58cf` | **ACCEPT**, commit `fe654109d98b4102e0b957758ea0c4b1212e42ef`. Blob returned to original SHA |
| Repeat old expected-SHA write | original `23033e7aaf8faa662ace313032c801e83c4ab076` | **ACCEPT**, commit `5e94d9791f83345311ade5c57d37cae6cd15dbb5` (ABA) |

**First failure:** GitHub Contents file-SHA compare-and-swap is neither a persistent monotonic revision fence nor a trustworthy high-watermark, **even when history only advances by ordinary commits**. A normal authorized writer can write old content in a new commit, thereby restoring the original blob SHA; the old expected hash then becomes valid again. No credentialed attacks or force-push were necessary. This counterexample is not evidence that GitHub itself or its security controls are compromised.

## Candidate consistency improvement, not a trust-root claim

`scripts/qualification/vip_witness_chain_probe.py` provides a dependency-free deterministic oracle for the limited property of **hash-chained, strictly increasing revisions**. It demands separately supplied:
1. pinned `(realm, audience, signer, minimum revision, minimum head hash)`, and
2. an independently authenticated **current head hash**.

Without *both*, the oracle returns BLOCKED. It rejects changed signer/realm/audience, broken links, duplicate/skip revision, old head, equivocation, and truncated history. It reproduces the blob-CAS ABA negative. This is **NOT** a witness service: its floor and latest-head arguments are test values and can be forged by a bad caller. The hashes do not provide issuer signature authentication. A truncated valid chain is indistinguishable from an honest historical prefix without an independently authenticated current head.

### Accept / adapt / defer / reject

- **REJECT:** a bare GitHub Contents blob-SHA or file revision as the VIP witness trust root (directly falsified Rep-A).
- **ADAPT:** TUF signed metadata version/freeze concepts and Zanzibar causal-consistency requirements into our signed snapshot/current-head negative tests; prior art is a set of test oracles, **not** a new service dependency.
- **CONDITIONAL:** a public protected append-only Git ref/commit-history authority may be a low-cost witness only after live qualification of atomic branch-head updates, rule bypass, independent writer administration, read-after-write, branch restore, clock/outage, and cryptographic binding. Protection against the repo administrator must not be claimed if that administrator can bypass/disable rules or serve obsolete state.
- **DEFER:** a new online IAM/witness daemon or subscription until a consuming workload and the trust model justify it; independent on-prem signer/witness deployment remains a distinct owner-controlled later gate.
- **PRESERVE:** current U2c Ed25519 signer/realm/audience semantics, existing VIP auth provider paths, free public CI, and no TrueNAS APPLY.

## Exact execution / next gates

The public CI lane invokes both `vip_membership_state_explorer.py` and `vip_witness_chain_probe.py`; the latter must succeed only if unsafe file-SHA behavior is recognized and candidate chain consistency is shown **under its stated external-trust assumptions**.

Next highest READY:
1. Independent writer/reader Git ref-level CAS feasibility, with explicit repo administrator and key custody bounds (no real credentials on public runners).
2. Demonstrate a separately pinned current-head read that survives local complete rollback and detects remote rollback/fork; classify failure/UNKNOWN as deployment BLOCKED.
3. Only after a trust envelope is earned, wire opt-in membership readiness, atomic observe→plan→apply→verify, cancel stale in-flight logins/transfers, and revoke active connections.
4. Actual mixed ONLINE_SESSION/WORKLOAD gameplay qualification using existing `mc-server-images#72` 26.3 public live fixtures.
5. Source custody, human/automation/agent journeys, and independent owner release gates.

Never claim live membership admission, production trust, verified current time, autonomous recurring cloud actor, deployment, or release because these SOURCE tests pass.
