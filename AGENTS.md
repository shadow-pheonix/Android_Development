# AGENTS.md

## Mandatory entrypoint

This file is mandatory for every agent, assistant, contributor, script, and automated development task in this repository.

Before changing source code, build files, workflows, configuration, or project structure, **read this file completely and follow its routing rules**.

Detailed guidance:

- [Task routing](docs/ROUTING.md)
- [Development workflow](docs/WORKFLOW.md)

## Repository model

`Android_Development` is a multi-app Android monorepo.

- New apps normally live at `apps/<AppName>/`.
- Each app should remain independently understandable and buildable.
- Shared code/tooling belongs in `shared/` only when it has a genuine multi-app use case.
- The existing root-level Android project is a legacy/test project. **Do not move or restructure it unless explicitly asked.**

## Mandatory task routing

Route the task before editing anything.

### Route A — brand-new app

Use this route when the requested app does not yet exist in the repository.

**Required order:**

**prepare locally → create `apps/<AppName>/` locally → complete development locally → validate locally → review final diff → commit/push finalized batch → GitHub Actions build/test → APK/artifact**

Rules:

1. **MUST develop the app locally first.**
2. **MUST complete and verify the intended development batch locally before committing app files to GitHub.**
3. **MUST NOT use direct GitHub file creation/editing as the normal development environment.**
4. GitHub Actions is used after the local batch is ready for authoritative Android-specific build/test validation and APK generation.

### Route B — existing app, including a fresh chat

Use this route when the app already exists in `apps/<AppName>/` or as an explicitly identified legacy/root project.

**Required order:**

**read root `AGENTS.md` → clone/sync current GitHub repository locally → identify exact target app → read app-level instructions → develop locally → validate locally → review final diff → commit/push coherent changes → GitHub Actions build/test → APK/artifact**

Rules:

1. **MUST clone the current repository locally if no current local checkout exists.**
2. If a local checkout exists, **MUST fetch/sync the relevant branch/commit before editing**.
3. **MUST inspect the existing app, its build files, README, and app-level `AGENTS.md` if present before modifying it.**
4. **MUST continue from the existing source.** Do not recreate the app from memory or start a disconnected replacement project.
5. **MUST complete and verify changes locally before committing/pushing them.**
6. Direct GitHub editing is not a substitute for the required local workflow.

### Route C — shared code, CI, docs, or repository infrastructure

Use this route when the task targets `shared/`, `.github/`, root documentation, or repository-wide infrastructure rather than one app.

1. **MUST work locally first and inspect the current repository state before editing.**
2. Determine which apps are affected before running validation.
3. Validate only affected apps unless a shared change legitimately requires broader testing.
4. Do not restructure unrelated apps as a side effect.

## Local-first rules

These rules apply to every route:

1. **Develop locally first.**
2. **Run all relevant locally available checks first**: syntax/structure checks, targeted tests, static inspection, patch verification, and diff review.
3. **Inspect the final diff before pushing.** Ensure the change is intentional, scoped, and free of secrets, temporary files, and unrelated edits.
4. **Do not push knowingly broken, partial, placeholder, or trial-and-error implementations merely to discover basic errors in CI.**
5. **Use short, bounded, resumable validation cycles.** Preserve successful intermediate work and do not repeat expensive steps without reason.
6. For substantial work, establish a known-good baseline and then change incrementally.

## GitHub and CI role

GitHub is the repository of record for finalized changes. GitHub Actions is the authoritative environment for Android SDK/NDK-dependent compilation, lint, tests, and APK generation when those checks are not available locally.

CI must be routed to the affected app only whenever practical:

- app-specific workflows should use path filters such as `apps/ExampleApp/**`;
- unrelated apps should not build for an app-only change;
- docs-only changes should not trigger Android builds unless the workflow itself is being tested;
- shared changes may trigger multiple affected apps when justified;
- APK/artifact names must clearly identify the app and build type.

If CI exposes an Android/environment-specific failure, make the fix in the local working copy, validate locally as far as possible, then push a focused correction.

## App-level instructions

An app may contain `apps/<AppName>/AGENTS.md`.

- Root `AGENTS.md` is always mandatory.
- App-level instructions add app-specific requirements.
- App-level instructions do not cancel mandatory root workflow, safety, secrets, or local-first requirements unless the root file explicitly permits it.

## Secrets and signing

Never commit private signing keys, passwords, tokens, credentials, or secrets into source, workflows, patches, logs, or repository history. Use approved secret storage or temporary development signing where appropriate.

## Ready-to-push gate

A change may be pushed only when all applicable items are true:

- intended work for the current batch is complete;
- current repository/app source was synced before development;
- locally available checks passed;
- final diff was reviewed;
- target app boundaries were respected;
- no accidental files, secrets, or unrelated changes are present;
- the batch is ready for authoritative CI validation.

If any applicable item is false, continue locally instead of pushing.
