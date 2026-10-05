# AGENTS.md

## Mandatory entrypoint

This file is mandatory for every agent, assistant, contributor, script, and automated development task in this repository.

Before changing source code, build files, workflows, configuration, or project structure, **read this file completely and follow its routing rules**.

Detailed guidance:

- [Task routing](docs/ROUTING.md)
- [Development workflow](docs/WORKFLOW.md)
- [Mandatory diagnostics standard](docs/DIAGNOSTICS.md)

## Mandatory terminology: chat-local workspace

For AI/assistant work performed from a ChatGPT chat, every instruction in this repository that says **local**, **locally**, **local checkout**, **local working copy**, **local environment**, or **local-first** means the **chat-local workspace**: the filesystem/runtime directly attached to the current chat/agent session (for example, its sandbox or container).

It **does not** mean the user's Windows PC, Windows account, desktop session, remote desktop, ADB host, `windows-bridge`, or any other connector/tool that reaches the user's machine.

Rules:

1. **MUST use the chat-local workspace for the repository's local-first development and validation steps.**
2. **MUST NOT search for, connect to, invoke, or modify `windows-bridge` merely to satisfy a requirement to work “locally”.**
3. `windows-bridge` is **opt-in only**. Use it only when the user explicitly asks to use their Windows machine or explicitly asks to use `windows-bridge` for the current task.
4. For an existing repository/app task, the normal source handoff is a **ZIP uploaded by the user containing the latest GitHub commit**. If no current trusted chat-local snapshot exists, **MUST ask the user to upload that latest-commit ZIP before editing**.
5. After upload, **MUST extract the ZIP into the chat-local workspace and develop from those extracted files**. Do not use `git clone`, the user's Windows checkout, or GitHub-file-by-file reconstruction as the normal source-ingest path.
6. **MUST record the base commit SHA associated with the uploaded ZIP and verify the target GitHub branch still points to that commit before publishing changes.** If the branch has advanced, request a fresh ZIP of the new latest commit rather than silently merging or rebuilding the source snapshot from GitHub.
7. The GitHub connector/API may be used to verify repository/commit state and to publish the finalized, locally developed change. It is **not** the normal development workspace and is **not** a substitute for the user-uploaded source ZIP.
8. A phrase such as “current local checkout” below therefore means the repository snapshot/worktree extracted into the **chat-local workspace**, not a checkout on the user's computer.

These definitions apply in fresh chats as well as continuing chats unless the user explicitly overrides them.

## Repository model

`Android_Development` is a multi-app Android monorepo.

- New apps normally live at `apps/<AppName>/`.
- Each app should remain independently understandable and buildable.
- Shared code/tooling belongs in `shared/` only when it has a genuine multi-app use case.
- The existing root-level Android project is a legacy/test project. **Do not move or restructure it unless explicitly asked.**

## Mandatory app baseline

Every Android app developed in this repository **MUST include diagnostics export from its initial development baseline**.

- The app **MUST provide a user-accessible `Export diagnostics` action** in builds used for development/testing.
- The exported ZIP **MUST be saved under user-visible internal/shared storage at `AppDebug/<AppName>/`** (normally `/storage/emulated/0/AppDebug/<AppName>/` on standard Android devices), using Android storage APIs that remain compatible with scoped storage.
- The ZIP **MUST contain bounded application logs plus crash, device, app, and build information sufficient for debugging**.
- Logs **MUST rotate/be bounded** so diagnostics cannot grow without limit.
- Diagnostics **MUST NOT contain passwords, auth tokens, signing material, private keys, or unnecessary sensitive user content**.
- The exporter and expected ZIP contents **MUST follow [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md)**.

An app is not considered ready for its first APK handoff until this diagnostics baseline is implemented and reviewed in the chat-local workspace, unless the user explicitly requests a temporary exception.

## Mandatory task routing

Route the task before editing anything.

### Route A — brand-new app

Use this route when the requested app does not yet exist in the repository.

**Required order:**

**read root `AGENTS.md` → obtain the latest-commit ZIP from the user → extract it into the chat-local workspace → create `apps/<AppName>/` in that workspace → complete development there (including mandatory diagnostics) → validate locally → review final diff → publish one coherent commit → GitHub Actions build/test → APK/artifact**

Rules:

1. **MUST start from the user's ZIP of the latest repository commit when modifying this existing monorepo.**
2. **MUST develop the app in the chat-local workspace first.**
3. **MUST complete and verify the intended development batch in the chat-local workspace before committing app files to GitHub.**
4. **MUST implement the mandatory diagnostics baseline before the first APK handoff.**
5. **MUST NOT use direct GitHub file creation/editing as the normal development environment.**
6. GitHub Actions is used after the chat-local batch is ready for authoritative Android-specific build/test validation and APK generation.

### Route B — existing app, including a fresh chat

Use this route when the app already exists in `apps/<AppName>/` or as an explicitly identified legacy/root project.

**Required order:**

**read root `AGENTS.md` → obtain the user-uploaded ZIP of the latest GitHub commit → extract it into the chat-local workspace → verify the ZIP's base commit is still current → identify exact target app → read app-level instructions → verify diagnostics baseline exists → develop locally → validate locally → review final diff → publish one coherent commit → GitHub Actions build/test → APK/artifact**

Rules:

1. If no current trusted chat-local snapshot exists, **MUST request the latest-commit repository ZIP from the user before editing**.
2. **MUST extract and use that ZIP as the source working tree.** Do not `git clone` or reconstruct the repository through the GitHub connector as the normal workflow.
3. If a chat-local snapshot already exists, **MUST verify its recorded base commit still matches the intended GitHub branch before editing**. If it does not, request a fresh latest-commit ZIP.
4. **MUST inspect the existing app, its build files, README, app-level `AGENTS.md` if present, and diagnostics implementation before modifying it.**
5. If the app lacks the mandatory diagnostics baseline, **MUST add it as part of active development unless the user explicitly requests otherwise.**
6. **MUST continue from the extracted existing source.** Do not recreate the app from memory or start a disconnected replacement project.
7. **MUST complete and verify changes in the chat-local workspace before publishing them.**
8. Direct GitHub editing is not a substitute for the required chat-local workflow.
9. **MUST NOT use `windows-bridge` to obtain a checkout or run validation unless the user explicitly requested `windows-bridge` or their Windows machine for this task.**

### Route C — shared code, CI, docs, or repository infrastructure

Use this route when the task targets `shared/`, `.github/`, root documentation, or repository-wide infrastructure rather than one app.

1. **MUST start from the user-uploaded ZIP of the latest GitHub commit when no current trusted chat-local snapshot exists.**
2. **MUST work in the extracted chat-local workspace first and inspect the current repository state before editing.**
3. Determine which apps are affected before running validation.
4. Validate only affected apps unless a shared change legitimately requires broader testing.
5. Do not restructure unrelated apps as a side effect.

## Chat-local-first rules

These rules apply to every route:

1. **Develop in the chat-local workspace first.**
2. **Run all relevant checks available in the chat-local workspace first**: syntax/structure checks, targeted tests, static inspection, patch verification, and diff review.
3. **Inspect the final diff before publishing.** Ensure the change is intentional, scoped, and free of secrets, temporary files, and unrelated edits.
4. **Do not push knowingly broken, partial, placeholder, or trial-and-error implementations merely to discover basic errors in CI.**
5. **Use short, bounded, resumable validation cycles.** Preserve successful intermediate work and do not repeat expensive steps without reason.
6. For substantial work, establish a known-good baseline and then change incrementally.

## GitHub and CI role

GitHub is the repository of record for finalized changes. GitHub Actions is the authoritative environment for Android SDK/NDK-dependent compilation, lint, tests, and APK generation when those checks are not available in the chat-local workspace.

The GitHub connector/API may be used to:

- verify the target branch and base commit before development/publishing;
- publish the finalized local diff as a coherent commit;
- inspect GitHub Actions status/logs and retrieve build artifacts.

It should **not** be used to reconstruct the repository file-by-file as the normal development source when the user can provide the latest-commit ZIP.

CI must be routed to the affected app only whenever practical:

- app-specific workflows should use path filters such as `apps/ExampleApp/**`;
- unrelated apps should not build for an app-only change;
- docs-only changes should not trigger Android builds unless the workflow itself is being tested;
- shared changes may trigger multiple affected apps when justified;
- APK/artifact names must clearly identify the app and build type.

If CI exposes an Android/environment-specific failure, make the fix in the chat-local working copy, validate there as far as possible, then publish a focused correction.

## App-level instructions

An app may contain `apps/<AppName>/AGENTS.md`.

- Root `AGENTS.md` is always mandatory.
- App-level instructions add app-specific requirements.
- App-level instructions do not cancel mandatory root workflow, diagnostics, safety, secrets, or chat-local-first requirements unless the root file explicitly permits it.

## Secrets and signing

Never commit private signing keys, passwords, tokens, credentials, or secrets into source, workflows, patches, logs, diagnostics bundles, or repository history. Use approved secret storage or temporary development signing where appropriate.

## Ready-to-push gate

A change may be published only when all applicable items are true:

- intended work for the current batch is complete;
- the source working tree came from the user's latest-commit ZIP (or from a still-current trusted ZIP snapshot already present in this chat);
- the recorded base commit was verified against the intended GitHub branch before publishing;
- mandatory diagnostics support is present for app-development work;
- checks available in the chat-local workspace passed;
- final diff was reviewed;
- target app boundaries were respected;
- no accidental files, secrets, or unrelated changes are present;
- the batch is ready for authoritative CI validation.

If any applicable item is false, continue in the chat-local workspace instead of publishing.
