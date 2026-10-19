# Development Workflow

This document expands the mandatory chat-local-first rules in `AGENTS.md`.

## Terminology

For AI/assistant work in a ChatGPT conversation, **chat-local workspace** means the filesystem/runtime attached directly to the current chat/agent session (sandbox/container).

Throughout this document, words such as **local**, **locally**, **local checkout**, and **local working copy** refer to that chat-local workspace. They do **not** refer to the user's Windows PC or to `windows-bridge`.

**Do not search for or invoke `windows-bridge` merely because this workflow requires local development.** `windows-bridge` may be used only when the user explicitly asks to use their Windows machine or explicitly requests `windows-bridge` for the current task.

## 1. Reuse a matching chat-local snapshot; otherwise request the latest-commit ZIP

For an existing repository/app, first inspect whether the required repository files already exist in the chat-local workspace.

When an existing chat-local repository snapshot is present:

1. Determine the latest commit SHA of the intended GitHub branch.
2. Verify that the chat-local repository snapshot matches that commit **byte-for-byte**, using Git tree/blob metadata plus locally calculated hashes or an equivalent byte-accurate method.
3. If it matches, record that commit SHA as the base commit and reuse the existing chat-local files. **Do not ask the user to upload another ZIP.**
4. Confirm the target app/path before editing.
5. Verify again immediately before editing that the intended branch still points to the recorded base commit.

Only when the required chat-local files are missing, incomplete, cannot be verified byte-for-byte, or do not match the latest intended GitHub commit:

1. Ask the user to upload the ZIP for that exact latest commit.
2. In the same message, provide the direct exact-commit archive link: `https://github.com/shadow-pheonix/Android_Development/archive/<COMMIT_SHA>.zip`.
3. Record the exact base commit SHA for the ZIP.
4. Extract the ZIP into the chat-local workspace.
5. Confirm the target app/path before editing.
6. Verify through GitHub that the intended branch still points to the recorded base commit.

If the GitHub branch advances after the chat-local source snapshot was verified, **do not silently merge, clone, reconstruct, or import the newer repository state**. Ask the user to upload a fresh ZIP of the new latest commit and include the direct exact-commit archive link in the same message.

**Do not use `git clone` as the normal ChatGPT source-ingest workflow. Do not reconstruct the working tree file-by-file through the GitHub connector, download individual repository files as a substitute, or try alternate import methods merely to avoid requesting the ZIP.** The GitHub connector is for verification and final publication, not the primary development filesystem.

Do not substitute a checkout on the user's Windows machine for this step unless the user explicitly requested Windows-machine access.

## 2. Establish scope and baseline

For small changes, inspect the relevant files and current behavior.

For substantial, risky, or performance-sensitive changes, establish a known-good baseline first. Keep the baseline narrow and reproducible.

For app development, verify that the mandatory diagnostics baseline from [DIAGNOSTICS.md](DIAGNOSTICS.md) exists. New apps must include it before the first normal APK handoff; existing apps that lack it should gain it during active development unless explicitly deferred by the user.

## 3. Classify task weight and plan GitHub checkpoints

Before substantial implementation, classify the task by weight.

- **Very light / light**: no checkpoint branch is required. Complete the small change in the verified chat-local workspace, validate it, review the diff, and publish one coherent commit to `main`.
- **Anything heavier than light**: create a checkpoint plan and use a dedicated feature branch.

For medium/heavy/very-heavy work:

1. Define the natural implementation checkpoints before substantial coding. The checkpoint count must scale with task weight and recoverable milestones rather than a fixed number. Rough guidance: medium tasks commonly need about 2–3 checkpoints, heavy tasks about 3–5, and very heavy or multi-phase tasks may need more.
2. Create a dedicated feature branch from the verified base commit before publishing the first checkpoint.
3. Keep `main` unchanged while work is in progress.
4. After each coherent milestone, run the narrow relevant chat-local checks, review that checkpoint's diff, and publish the checkpoint commit to the feature branch.
5. Do not publish broken, placeholder, or speculative checkpoint states merely for safekeeping. The checkpoint itself must be coherent and locally validated.
6. The purpose of checkpoint commits is durable recovery: if the chat-local workspace is lost, completed checkpoints remain available on GitHub without exposing incomplete work on `main`.

## 4. Develop in the chat-local workspace

Make source, resource, script, workflow, and configuration changes in the verified chat-local working copy, whether it was reused directly or extracted from a required ZIP.

Prefer a small number of coherent changes over a large matrix of speculative experiments.

Do not use GitHub commits as scratch storage or CI as a syntax checker when the issue can be resolved in the chat-local workspace.

## 5. Validate in the chat-local workspace

Run the narrowest useful checks available in the chat-local workspace, for example:

- syntax/format checks;
- targeted unit tests;
- static inspection;
- Gradle configuration checks when available;
- manifest/resource inspection;
- script validation;
- diagnostics exporter/path/content checks;
- focused diff review.

The chat-local workspace may not contain the full Android SDK, emulator, NDK, or a physical device. That does not remove the chat-local-first requirement; perform every meaningful check that is available there.

Lack of an Android SDK in the chat-local workspace is **not** a reason to invoke `windows-bridge`. Use GitHub Actions for authoritative Android-specific checks after the local batch is ready, unless the user explicitly asked to use their Windows machine.

## 6. Review the final diff

Before publishing, verify:

- only intended files changed;
- mandatory diagnostics support is present for app-development work;
- diagnostics output targets `AppDebug/<AppName>/` and remains scoped-storage compatible;
- no temporary debugging files remain unless intentionally part of diagnostics;
- no secrets/signing material are present;
- no unrelated apps were modified;
- generated artifacts that should not be committed are absent;
- app/version/build changes are deliberate.

## 7. Publish checkpoints and finalize to GitHub

For a very-light/light task, publish only when the small batch is internally complete and verified in the chat-local workspace, then use one focused commit on `main`.

For anything heavier than light:

1. Publish each completed, coherent, locally validated checkpoint to the dedicated feature branch.
2. Do not advance `main` for intermediate checkpoints.
3. After all planned checkpoints are complete, run cumulative local validation and review the full feature-branch diff against the recorded base.
4. Run the relevant authoritative CI on the feature branch when the workflow supports branch/PR/manual execution.
5. Only when the complete task is verified good may the feature branch be merged or fast-forwarded into `main`. Prefer preserving checkpoint history unless repository policy or the user explicitly requests another merge method.
6. Immediately before merging, verify that `main` still matches the expected base lineage. If it advanced outside this task, stop and follow the fresh-source/base-verification rules rather than merging blindly.

For either workflow, if the intended base branch has advanced unexpectedly, obtain a fresh latest-commit ZIP from the user when required, including the direct exact-commit archive link in the request, rather than overwriting, merging, cloning, or reconstructing blindly.

When direct `git push` is unavailable from the chat runtime, the authorized GitHub connector/API may publish the exact validated checkpoint/final diff by creating the required Git objects/commits and advancing the appropriate feature branch or `main`. This is an acceptable publication mechanism because development and review already happened in the chat-local workspace.

GitHub-side file edits are not the development workspace.

## 8. Run authoritative Android CI

Apply the [mandatory APK build timing gate](#11-apk-delivery) before triggering CI that produces an APK, including CI started automatically by publishing changes.

Use GitHub Actions for checks that require the Android build environment. For checkpointed work, prefer running the final authoritative CI against the feature branch before merging to `main` when the workflow supports it.

CI should:

- build only the affected app whenever practical;
- run lint/tests appropriate to that app;
- create clearly named APK artifacts;
- avoid rebuilding unrelated apps.

## 9. Handle CI failures

When CI fails:

1. Inspect the exact failing step and relevant log lines.
2. Determine whether the failure is code-related or environment-specific.
3. Reproduce or reason about it in the chat-local workspace where possible.
4. Fix it in the same chat-local working copy.
5. Run chat-local checks again.
6. For checkpointed work, publish the focused correction to the same feature branch and re-run the relevant validation before merging; for an already-merged/direct light task, use a new focused correction path as appropriate.

Do not repeatedly make blind CI-only edits.

## 10. Device debugging

Every app must support the diagnostics export defined in [DIAGNOSTICS.md](DIAGNOSTICS.md).

For bugs that require a physical device:

- reproduce the problem;
- use the app's **Export diagnostics** action;
- retrieve the ZIP from `Internal storage/AppDebug/<AppName>/`;
- provide the ZIP together with reproduction steps;
- use screen recordings when useful for timing, UI, media, or interaction bugs;
- if the user explicitly requests a connected device/ADB/Windows bridge workflow, device logs may then be used directly.

Device access is a separate, explicit workflow. It is not implied by the term “local”.

Keep diagnostics bounded and avoid logging credentials, tokens, signing material, or unnecessary sensitive user content.

## 11. APK delivery

### Mandatory user choice before building

After completing a coherent implementation batch and before starting an APK build, **MUST ask the user: “Build the APK now, or wait until more implementations are complete?”** Wait for the user's explicit choice; do not assume that a request to implement a feature or continue work authorizes a build.

- **Build now:** build and validate the completed batch, then deliver the APK. An explicit user request to build that batch already supplies this choice; do not ask again for the same batch or necessary retries.
- **Build after more implementations:** preserve completed work, continue the user's authorized implementations and checks that do not produce an APK, and ask again when the next batch is ready. Do not package an APK or trigger APK-producing CI while the build is deferred or the question remains unanswered.

This applies to local APK packaging, manual CI runs, and pushes, PRs or merges that automatically start APK builds. Use checkpoint branches that do not trigger APK builds to preserve deferred work, and defer publication/merging that would trigger them. Required tests, lint and validation still apply; postponing packaging does not waive the checks required before APK delivery. Documentation-only changes do not need an APK build or a build-timing question unless the user requests an APK.

### Validated APK handoff

Deliver an APK only after the relevant build completes successfully.

For a new app, the first normal APK handoff requires the mandatory diagnostics baseline unless the user explicitly requested a temporary exception.

Record or surface enough build identity to trace the APK back to its source commit/workflow run when practical.
