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
4. For an existing repository/app task, **MUST first inspect whether the required repository files already exist in the chat-local workspace**. If they do, **MUST verify that the existing chat-local repository snapshot matches the latest intended GitHub branch commit byte-for-byte**. Verification may use Git tree/blob metadata plus locally calculated hashes or an equivalent byte-accurate method. If it matches, **MUST reuse those existing files and MUST NOT ask the user for another ZIP**. Record that matching commit SHA as the base commit.
5. Only when the required chat-local files are missing, incomplete, cannot be verified byte-for-byte, or do not match the latest intended GitHub commit, **MUST ask the user to upload a ZIP of that exact latest commit before editing**. Whenever asking for the ZIP, **MUST include a direct exact-commit archive link in the same message**, using `https://github.com/shadow-pheonix/Android_Development/archive/<COMMIT_SHA>.zip`. Do not use `git clone`, GitHub file-by-file reconstruction, individual-file downloads, or alternate repository-import methods as a substitute for requesting the ZIP.
6. After a required ZIP is uploaded, **MUST extract it into the chat-local workspace and develop from those extracted files**.
7. **MUST record the base commit SHA for the verified chat-local snapshot, whether it was reused or extracted from a ZIP, and verify the target GitHub branch still points to that commit both before editing and before publishing changes.** If the branch has advanced, request a fresh ZIP of the new latest commit and include the direct exact-commit archive link rather than silently merging, cloning, reconstructing, or importing the newer files from GitHub.
8. The GitHub connector/API may be used to verify repository/commit/tree/blob state and to publish the finalized, locally developed change. It is **not** the normal development workspace and is **not** a substitute for either a verified matching chat-local snapshot or, when needed, the user-uploaded source ZIP.
9. A phrase such as “current local checkout” below therefore means a verified matching repository snapshot/worktree in the **chat-local workspace**, including one extracted from a user-uploaded ZIP, not a checkout on the user's computer.

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

## Mandatory APK build timing

Every task route **MUST follow the [APK build timing and delivery gate](docs/WORKFLOW.md#11-apk-delivery)** before packaging an APK or triggering APK-producing CI. This gate takes precedence over the build/CI/APK steps in the routes below and in `docs/ROUTING.md`.

## Mandatory task routing

Route the task before editing anything.

### Route A — brand-new app

Use this route when the requested app does not yet exist in the repository.

**Required order:**

**read root `AGENTS.md` → inspect the chat-local workspace → verify any existing repository snapshot byte-for-byte against the latest intended GitHub commit → reuse it if it matches, otherwise request the exact latest-commit ZIP with its direct download link and extract it → create `apps/<AppName>/` in that workspace → complete development there (including mandatory diagnostics) → validate locally → review final diff → publish one coherent commit → GitHub Actions build/test → APK/artifact**

Rules:

1. **MUST start from an existing chat-local repository snapshot only when it has been verified byte-for-byte against the latest intended GitHub commit; otherwise MUST request and use the user's ZIP of that exact commit, including the direct download link when requesting it.**
2. **MUST develop the app in the chat-local workspace first.**
3. **MUST complete and verify the intended development batch in the chat-local workspace before committing app files to GitHub.**
4. **MUST implement the mandatory diagnostics baseline before the first APK handoff.**
5. **MUST NOT use direct GitHub file creation/editing as the normal development environment.**
6. GitHub Actions is used after the chat-local batch is ready for authoritative Android-specific build/test validation and APK generation.

### Route B — existing app, including a fresh chat

Use this route when the app already exists in `apps/<AppName>/` or as an explicitly identified legacy/root project.

**Required order:**

**read root `AGENTS.md` → inspect the chat-local workspace → verify any existing repository snapshot byte-for-byte against the latest intended GitHub commit → reuse it if it matches, otherwise request the exact latest-commit ZIP with its direct download link and extract it → verify the recorded base commit is still current → identify exact target app → read app-level instructions → verify diagnostics baseline exists → develop locally → validate locally → review final diff → publish one coherent commit → GitHub Actions build/test → APK/artifact**

Rules:

1. **MUST first inspect for an existing chat-local repository snapshot and verify it byte-for-byte against the latest intended GitHub branch commit.** If it matches, reuse it and do not request another ZIP.
2. If the required chat-local files are missing, incomplete, unverifiable, or mismatched, **MUST request the exact latest-commit repository ZIP from the user before editing and include its direct GitHub archive link in the same message**.
3. **MUST extract and use a required uploaded ZIP as the source working tree.** Do not `git clone`, reconstruct the repository through the GitHub connector, download files individually, or try alternate import paths as a substitute for the ZIP.
4. For either a reused verified snapshot or an uploaded ZIP, **MUST record the base commit and verify it still matches the intended GitHub branch before editing and again before publishing**. If the branch advances, request a fresh exact-commit ZIP and include its direct archive link.
5. **MUST inspect the existing app, its build files, README, app-level `AGENTS.md` if present, and diagnostics implementation before modifying it.**
6. If the app lacks the mandatory diagnostics baseline, **MUST add it as part of active development unless the user explicitly requests otherwise.**
7. **MUST continue from the extracted existing source.** Do not recreate the app from memory or start a disconnected replacement project.
8. **MUST complete and verify changes in the chat-local workspace before publishing them.**
9. Direct GitHub editing is not a substitute for the required chat-local workflow.
10. **MUST NOT use `windows-bridge` to obtain a checkout or run validation unless the user explicitly requested `windows-bridge` or their Windows machine for this task.**

### Route C — shared code, CI, docs, or repository infrastructure

Use this route when the task targets `shared/`, `.github/`, root documentation, or repository-wide infrastructure rather than one app.

1. **MUST first inspect for an existing chat-local repository snapshot and verify it byte-for-byte against the latest intended GitHub commit.** Reuse it when it matches; otherwise request the exact latest-commit ZIP from the user and include its direct archive link.
2. **MUST work in the verified chat-local workspace first and inspect the current repository state before editing.**
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

## Mandatory checkpoint branch policy

Before implementation, classify the task by weight.

- **Very light / light tasks**: a small, low-risk, narrowly scoped change such as a typo, simple documentation edit, tiny configuration adjustment, or similarly bounded change. These do **not** require a checkpoint feature branch and may follow the normal verified workflow to one coherent commit on `main`.
- **Anything heavier than light**: medium, heavy, very heavy, multi-phase, risky, or otherwise substantial work. These **MUST use a dedicated feature branch with planned checkpoint commits** so completed work is preserved on GitHub even if the chat-local workspace is lost or deleted.

For checkpointed work:

1. **MUST plan the checkpoint milestones before substantial implementation begins.** The number of checkpoints must scale with task weight and natural implementation boundaries. Use enough checkpoints to bound potential work loss, but do not create meaningless micro-commits. As guidance, medium work will often need about 2–3 checkpoints, heavy work about 3–5, and very heavy/multi-phase work may need more; these are guidance, not fixed quotas.
2. **MUST create a dedicated feature branch from the verified base commit before publishing the first checkpoint.** Keep `main` unchanged while the checkpointed implementation is in progress.
3. Each checkpoint **MUST represent a coherent, recoverable milestone**. Before publishing a checkpoint, run the narrow relevant chat-local checks available for that milestone and review its diff.
4. **MUST publish each completed checkpoint to the feature branch** after it is locally validated. The purpose is durable progress preservation, not using GitHub as scratch storage or CI as a syntax checker.
5. **MUST NOT intentionally checkpoint known-broken, placeholder, or trial-and-error states** merely to save them. Fix ordinary failures locally first.
6. After all planned checkpoints are complete, **MUST validate the complete cumulative change**, review the full feature-branch diff against its base, verify no unrelated files/secrets/temp files are present, and run the relevant authoritative CI on the feature branch when the workflow supports it.
7. Only after the complete checkpointed task is verified good may the feature branch be **merged or fast-forwarded into `main`**. Prefer a merge method that preserves the checkpoint history unless repository policy or the user explicitly requires otherwise.
8. Immediately before merging, verify that `main` still matches the expected base lineage. If `main` advanced outside this task, **do not merge blindly**; follow the repository's fresh-source/base-verification rules and reconcile only with explicit, verified source state.
9. A feature-branch checkpoint is an intentional exception to the rule that unfinished work should not be published to `main`; checkpoint commits are allowed only on the dedicated feature branch and only when the checkpoint itself is coherent and validated.

## GitHub and CI role

GitHub is the repository of record for finalized changes. GitHub Actions is the authoritative environment for Android SDK/NDK-dependent compilation, lint, tests, and APK generation when those checks are not available in the chat-local workspace.

The GitHub connector/API may be used to:

- verify the target branch and base commit before development/publishing;
- publish a verified very-light/light change directly when appropriate, or publish validated checkpoint commits to a dedicated feature branch for heavier work and merge that branch only after final verification;
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

- for a direct-to-`main` very-light/light task, the intended work for the batch is complete; for checkpointed work, the current checkpoint is coherent and validated before a feature-branch push, and the entire task is complete before merging to `main`;
- the source working tree is a chat-local repository snapshot verified byte-for-byte against the recorded latest intended GitHub commit, whether reused directly or extracted from a required user-uploaded ZIP;
- the recorded base commit was verified against the intended GitHub branch before editing and again before publishing;
- mandatory diagnostics support is present for app-development work;
- checks available in the chat-local workspace passed;
- final diff was reviewed;
- target app boundaries were respected;
- no accidental files, secrets, or unrelated changes are present;
- the batch/checkpoint is ready for the next permitted publication stage, and checkpointed work has completed full validation before any merge to `main`.

If any applicable item is false, continue in the chat-local workspace instead of publishing.
