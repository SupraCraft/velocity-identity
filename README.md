# SupraCraft Velocity Identity

Velocity Identity is a stock-Velocity plugin for explicit, provider-neutral identity and admission.

The current public MVP preserves normal Microsoft/Mojang authentication and adds an explicitly configured guest admission path for controlled actor/gym use. Federated human and workload authentication classes are modeled but fail closed until their credential transport and verifier are independently qualified.

## Safety boundary

Velocity Identity changes only its own in-memory effective policy. It does not rewrite `velocity.toml`, backend configuration, firewall rules, or identity-provider configuration. Backends continue to receive ordinary Velocity player-information forwarding.

The default configuration is Microsoft-only. Guest admission must be selected explicitly by virtual host and constrained to explicitly allowed backend server names. Authentication failures never downgrade into guest access.

## Operating model

Runtime reconciliation uses one sequence for humans, automation, and agents:

```text
observe/discover -> plan -> apply -> verify
```

The plugin writes machine-readable evidence under its Velocity data directory in `state/`:

- `observation.json`
- `plan.json`
- `apply.json`
- `verification.json`

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

An absent configuration is deliberately safe: Microsoft/Mojang authentication only.

## Project posture

The public repository is a constructive projection of a separately governed development plane. Public builds and tests operate only on the already-public source tree and require no private-repository credentials.
