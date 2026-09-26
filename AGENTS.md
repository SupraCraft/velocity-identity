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
