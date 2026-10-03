# AGENTS.md

## Mandatory Development Workflow

This policy is **mandatory for all agents, assistants, contributors, and automated development work in this repository**.

Before making any source-code, build-script, workflow, configuration, or project change, you **MUST read and follow this entire `AGENTS.md` file**.

## Repository Architecture: Multi-App Monorepo

This repository is intended to host multiple independent Android applications in a single GitHub repository.

### Default app location

New Android apps **MUST normally be created under**:

```text
apps/<AppName>/
```

Example:

```text
Android_Development/
├── AGENTS.md
├── README.md
├── .github/
│   └── workflows/
├── apps/
│   ├── TestApp/
│   ├── VideoPlayer/
│   └── UtilityApp/
└── shared/
```

Each `apps/<AppName>/` directory should be treated as an independent application workspace. Unless there is a deliberate shared architecture, each app should maintain its own Gradle project/configuration, application ID, versioning, source, resources, diagnostics, tests, and artifact output.

### App isolation

1. **MUST avoid unrelated cross-app changes.** Work on the requested app only unless a shared change is explicitly required.
2. **MUST keep app-specific dependencies/configuration inside that app** when practical.
3. **MUST NOT couple unrelated apps** merely to reduce a small amount of duplicated configuration.
4. Shared code/tooling may live under `shared/` only when it has a genuine multi-app use case.
5. An app may include its own `AGENTS.md`. When present, the root `AGENTS.md` remains mandatory and the app-level file adds more specific rules; it does not override mandatory root safety/workflow requirements unless explicitly stated.
6. Existing legacy/root-level projects may remain until deliberately migrated; do not perform a repository-wide restructure as a side effect of unrelated work.

### CI isolation

GitHub Actions should be designed so that a change to one app does not unnecessarily build every app in the monorepo.

- Prefer path filters for app-specific workflows, for example `apps/ExampleApp/**`.
- Prefer reusable workflows when they reduce duplication without creating tight coupling.
- Build/test only the affected app unless a shared component change legitimately requires broader validation.
- APK/artifact names should identify the app and build type clearly.

### When to split an app into its own repository

Do **not** create a separate GitHub repository for every prototype or small app by default.

A separate repository should be considered when an app becomes a substantial standalone project and materially benefits from independent:

- release/version lifecycle;
- collaborators or repository permissions;
- issues/project management;
- secrets or CI/CD infrastructure;
- public/private visibility;
- long-term maintenance/history.

Until one of those needs exists, prefer keeping the app under `apps/<AppName>/`.

If an app later needs independence, it may be extracted into its own repository deliberately. Do not split it casually during unrelated development.

### Local-First Development Is Required

1. **MUST develop locally first.** Prepare, edit, inspect, and refine source code, scripts, patches, and configuration in the local working environment before committing them to GitHub.
2. **MUST validate locally first.** Run every locally available validation that is relevant, including syntax checks, structural checks, targeted tests, static inspection, diff review, and other inexpensive checks.
3. **MUST inspect the final diff before committing.** Confirm that the change is intentional, coherent, scoped correctly, and does not contain temporary debugging code, accidental files, secrets, or unrelated modifications.
4. **MUST commit coherent, reviewable changes.** Push a feature/fix only after the local implementation is internally consistent and ready for authoritative build/test validation.
5. **MUST use GitHub Actions primarily for authoritative validation and artifact generation** such as Android compilation, lint, tests, APK generation, and other Android SDK/NDK-dependent checks.
6. **MUST NOT use GitHub Actions as the primary development loop** when the same source/script issue can reasonably be developed, inspected, or corrected locally first.
7. **MUST NOT intentionally push knowingly broken, partial, trial-and-error, or placeholder implementations merely to discover basic syntax or patching errors in CI.**
8. If GitHub Actions exposes an environment-specific or Android-build-specific failure, **MUST inspect the exact failing step/log, reproduce or reason about it locally where possible, make the focused fix locally, validate it locally, and only then push the correction.**
9. **MUST preserve completed work and successful intermediate results.** Do not repeat expensive successful steps without a technical reason.
10. **MUST prefer short, bounded, resumable validation cycles.** Avoid unnecessary long-running or exhaustive jobs during development.
11. For substantial changes, **MUST establish a known-good baseline first**, then make and validate changes incrementally.
12. Where practical, feature development should live in the actual project source tree and normal commits rather than being injected dynamically by CI scripts.

### GitHub / CI Role

GitHub is the repository of record for finalized development changes.

GitHub Actions is the authoritative environment for final Android-specific validation when required, but it is **not a substitute for local development and local inspection**.

A normal feature workflow should be:

**Read root AGENTS.md → read target app AGENTS.md if present → sync exact target source → develop locally inside the target app → validate locally → inspect final diff → commit/push coherent change → run only the relevant GitHub Actions validation → inspect any CI-specific failure → fix locally → push focused correction → produce APK/artifact.**

### Environment Constraint

The assistant's local environment may not always contain a complete Android SDK, Android Studio, emulator, device connection, or every native dependency.

That limitation **does not remove the local-first requirement**. In such cases:

- perform all meaningful local editing, inspection, syntax/structure validation, patch verification, and diff review that is available;
- do not use CI for avoidable trial-and-error;
- use GitHub Actions only for the Android/NDK/build validation that genuinely requires that environment.

### Secrets and Signing Material

- Never commit private signing keys, passwords, tokens, credentials, or secrets into source code, workflow YAML, patches, logs, or repository history.
- Use approved secret storage or temporary development signing mechanisms when required.

### Definition of Ready to Push

A change is ready to push only when:

- the intended implementation is complete for the current batch;
- locally available checks have passed;
- the final diff has been reviewed;
- the target app boundary has been respected and unrelated apps are unchanged unless necessary;
- no temporary/debug-only material remains unless intentionally part of diagnostics;
- no secrets or unrelated changes are present;
- the change is ready for authoritative CI validation.

If any of these conditions are not satisfied, continue working locally instead of pushing an intermediate experiment.
