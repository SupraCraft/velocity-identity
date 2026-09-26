# Public projection instructions

This repository is a public, buildable projection of SupraCraft Velocity Identity.

Treat repository state as a product/build surface, not as a place to introduce private deployment configuration, credentials, identity-provider secrets, private experiment evidence, or environment-specific state.

Changes and pull requests may be useful proposals and evidence. Maintainers reconcile accepted changes through the project authority before the next governed projection.

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
- Paper mixed online-session/synthetic behavior is UNKNOWN until the blocking compatibility matrix passes.

Provider-family qualification must use real provider services instantiated in the test environment. Mocks, synthetic Yggdrasil/session servers, and fake provider implementations are not acceptance evidence.
