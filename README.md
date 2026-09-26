# Velocity Identity Plugin (VIP)

Velocity Identity Plugin (VIP) is a stock-Velocity plugin for explicit, provider-neutral identity and admission.

The current public implementation consumes Velocity-verified online sessions from the process-wide Minecraft session authority, supports explicitly selected guest admission, and supports externally provisioned Ed25519 workload identities through Velocity's standard login-plugin-message API. Mojang is the default online-session authority; qualified Yggdrasil-compatible authorities such as Drasl, Minecrauth, and AsterYggdrasil have been exercised through the same ONLINE_SESSION contract.

## Safety boundary

Velocity Identity changes only its own in-memory effective policy. It does not rewrite `velocity.toml`, backend configuration, firewall rules, or identity-provider configuration. Backends continue to receive ordinary Velocity player-information forwarding.

The default configuration is ONLINE_SESSION-only and therefore uses Velocity's configured process-wide session authority. Guest admission must be selected explicitly by virtual host and constrained to explicitly allowed backend server names. Authentication failures never downgrade into guest access.

## Operating model

Runtime reconciliation uses one sequence for humans, automation, and agents:

```text
observe/discover -> plan -> apply -> verify
```

The plugin writes machine-readable evidence under its Velocity data directory in `state/`:

- `observation.json`
- `plan.json`
- `apply.json`
- `rollback.json` (written when a failed reconciliation restores the prior policy)
- `verification.json`\n- `workload-trust.json`

Unknown runtime facts remain explicit unknowns rather than inferred from unsupported internals.

## Build

Requirements: Java 25. The repository carries a pinned Gradle Wrapper.

```sh
./gradlew --no-daemon clean build
```

The blocking compatibility baseline is Velocity 4.2.0. A different API/runtime target can be selected for compatibility probes:

```sh
./gradlew --no-daemon clean build -PvelocityVersion=4.2.1-SNAPSHOT
```

A disposable Velocity runtime can be launched with:

```sh
./gradlew --no-daemon runVelocity
```

Generated runtime state lives under `run/` and is not authoritative.

## Configuration

Copy `config/velocity-identity.properties.example` to the plugin's Velocity data directory as `velocity-identity.properties` and change only the admission paths you intend to enable.

An absent configuration is deliberately safe: ONLINE_SESSION only. With no Velocity session-server override, that is the normal Mojang session authority.

## Project posture

The public repository is a constructive projection of a separately governed development plane. Public builds and tests operate only on the already-public source tree and require no private-repository credentials.


## Authority and interoperability

VelocityIdentity declares the Velocity capability `velocity-identity-authority`; Velocity refuses a second plugin that claims the same capability ID.

The authority accepts new logins only after a desired policy has been observed, planned, applied, and independently verified. A later reconciliation failure may continue only with a previously verified last-known-good policy.

Runtime authorization covers both registered backend changes and modern external-host transfer. Guest and other synthetic identities deny external transfer by default.

VanillaCord requires no identity-specific changes: its existing Velocity modern-forwarding v1 path consumes the forwarded UUID, name, and profile properties.

Paper 1.21.4 modern forwarding has been qualified with ONLINE_SESSION and explicit synthetic identities on the same unchanged backend. Provider verification outages and workload authentication failures remain fail-closed.
