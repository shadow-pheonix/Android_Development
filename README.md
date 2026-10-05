# Android_Development

A multi-app Android development monorepo for building, testing, debugging, and producing APKs from one GitHub repository.

## Mandatory for AI/agent development

Before making any change, read **[AGENTS.md](./AGENTS.md)** completely. Its routing, diagnostics, and chat-local-first rules are mandatory.

For work performed from ChatGPT, **chat-local workspace** means the filesystem/runtime attached directly to the current chat/agent session (sandbox/container). It does **not** mean the user's Windows PC and it does **not** authorize use of `windows-bridge`. `windows-bridge` is used only when the user explicitly requests it or explicitly asks to perform the task on their Windows machine.

The core rule is simple:

> **Develop and verify in the chat-local workspace first. Commit only a coherent, checked batch. Use GitHub Actions afterward for authoritative Android build/test validation and APK generation.**

For an app that already exists, a fresh chat must first synchronize the current repository into the chat-local workspace and continue from that existing source. Prefer `git clone`/fetch when available; when direct clone of the private repository is unavailable in the chat runtime, use the authorized GitHub connector/materialization path to obtain the current source in the chat-local workspace.

Every app developed here must also provide an **Export diagnostics** feature that saves a debugging ZIP under `Internal storage/AppDebug/<AppName>/`. See [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md).

## Repository layout

```text
Android_Development/
├── AGENTS.md
├── README.md
├── docs/
│   ├── ROUTING.md
│   ├── WORKFLOW.md
│   └── DIAGNOSTICS.md
├── .github/
│   └── workflows/
├── apps/
│   ├── AppOne/
│   └── AppTwo/
├── shared/
└── <legacy root test app>
```

New apps normally belong under `apps/<AppName>/`. Each app should remain independently understandable and buildable, with its own package/application ID, versioning, source, resources, diagnostics, and artifact output.

The current root-level Android project is a legacy/test project and should not be moved unless migration is explicitly requested.

## Start here

- **Agent/task routing:** [docs/ROUTING.md](docs/ROUTING.md)
- **Development and validation flow:** [docs/WORKFLOW.md](docs/WORKFLOW.md)
- **Mandatory diagnostics standard:** [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md)

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
