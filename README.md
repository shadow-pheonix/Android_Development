# Android_Development

A multi-app Android development monorepo for building, testing, debugging, and producing APKs from one GitHub repository.

## Mandatory for AI/agent development

Before making any change, read **[AGENTS.md](./AGENTS.md)** completely. Its routing, diagnostics, source-handoff, and chat-local-first rules are mandatory.

For work performed from ChatGPT, **chat-local workspace** means the filesystem/runtime attached directly to the current chat/agent session (sandbox/container). It does **not** mean the user's Windows PC and it does **not** authorize use of `windows-bridge`. `windows-bridge` is used only when the user explicitly requests it or explicitly asks to perform the task on their Windows machine.

The core workflow is:

> **The user uploads a ZIP of the latest GitHub commit. Extract and develop from that exact snapshot in the chat-local workspace. Validate and review locally. Publish one coherent commit to GitHub only after the batch is ready, then use GitHub Actions for authoritative Android build/test validation and APK generation.**

### ChatGPT source handoff

For an existing app or any change to this monorepo:

1. The user uploads a ZIP corresponding to the latest intended GitHub commit.
2. The agent records the base commit SHA and extracts the ZIP into the chat-local workspace.
3. The agent verifies the intended GitHub branch still points to that base commit.
4. Development, validation, and diff review happen in the extracted chat-local working tree.
5. The GitHub connector/API may then publish the finalized local diff and inspect CI/build artifacts.

If the GitHub branch has advanced since the ZIP was created, the agent should request a fresh latest-commit ZIP rather than silently cloning, merging, or reconstructing the repository through GitHub file APIs.

**Do not use `git clone` as the normal ChatGPT source-ingest workflow. Do not use `windows-bridge` unless explicitly requested. Do not reconstruct the repository file-by-file from GitHub when the user can provide the source ZIP.**

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
