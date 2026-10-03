# Android_Development

A multi-app Android development monorepo for building, testing, debugging, and producing APKs from one GitHub repository.

## Mandatory for AI/agent development

Before making any change, read **[AGENTS.md](./AGENTS.md)** completely. Its routing and local-first rules are mandatory.

The core rule is simple:

> **Develop and verify locally first. Commit only a coherent, checked batch. Use GitHub Actions afterward for authoritative Android build/test validation and APK generation.**

For an app that already exists, a fresh chat must first clone or synchronize the current repository locally and continue from that existing source.

## Repository layout

```text
Android_Development/
├── AGENTS.md
├── README.md
├── docs/
│   ├── ROUTING.md
│   └── WORKFLOW.md
├── .github/
│   └── workflows/
├── apps/
│   ├── AppOne/
│   └── AppTwo/
├── shared/
└── <legacy root test app>
```

New apps normally belong under `apps/<AppName>/`. Each app should remain independently understandable and buildable, with its own package/application ID, versioning, source, resources, and artifact output.

The current root-level Android project is a legacy/test project and should not be moved unless migration is explicitly requested.

## Start here

- **Agent/task routing:** [docs/ROUTING.md](docs/ROUTING.md)
- **Development and validation flow:** [docs/WORKFLOW.md](docs/WORKFLOW.md)

## App strategy

Keep prototypes, personal utilities, experiments, and small-to-medium apps in this monorepo by default.

Consider a separate repository only when an app materially benefits from independent release management, collaborators/permissions, issues, secrets/CI, visibility, or long-term history.

An app can be extracted later; it does not need a separate repository from day one.

## CI strategy

CI should be routed to the affected app only whenever practical.

- App-specific workflows should use `paths` filters.
- Documentation-only changes should not trigger Android builds by default.
- Shared changes should validate only the apps they actually affect.
- APK artifact names should identify the app and build type.
