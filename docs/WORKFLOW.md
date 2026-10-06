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

## 3. Develop in the chat-local workspace

Make source, resource, script, workflow, and configuration changes in the verified chat-local working copy, whether it was reused directly or extracted from a required ZIP.

Prefer a small number of coherent changes over a large matrix of speculative experiments.

Do not use GitHub commits as scratch storage or CI as a syntax checker when the issue can be resolved in the chat-local workspace.

## 4. Validate in the chat-local workspace

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

## 5. Review the final diff

Before publishing, verify:

- only intended files changed;
- mandatory diagnostics support is present for app-development work;
- diagnostics output targets `AppDebug/<AppName>/` and remains scoped-storage compatible;
- no temporary debugging files remain unless intentionally part of diagnostics;
- no secrets/signing material are present;
- no unrelated apps were modified;
- generated artifacts that should not be committed are absent;
- app/version/build changes are deliberate.

## 6. Publish the finalized change to GitHub

Publish only when the current batch is internally complete and verified in the chat-local workspace.

Before publishing, verify again that the intended GitHub branch still points to the recorded base commit. If it has advanced, stop and obtain a fresh latest-commit ZIP from the user, including the direct exact-commit archive link in the request, rather than overwriting, merging, cloning, or reconstructing blindly.

Prefer one focused commit with a clear message for one coherent batch.

When direct `git push` is unavailable from the chat runtime, the authorized GitHub connector/API may publish the exact local diff by creating the required Git objects/commit and advancing the target branch. This is an acceptable publication mechanism because development and review already happened in the chat-local workspace.

GitHub-side file edits are not the development workspace.

## 7. Run authoritative Android CI

Use GitHub Actions for checks that require the Android build environment.

CI should:

- build only the affected app whenever practical;
- run lint/tests appropriate to that app;
- create clearly named APK artifacts;
- avoid rebuilding unrelated apps.

## 8. Handle CI failures

When CI fails:

1. Inspect the exact failing step and relevant log lines.
2. Determine whether the failure is code-related or environment-specific.
3. Reproduce or reason about it in the chat-local workspace where possible.
4. Fix it in the same chat-local working copy.
5. Run chat-local checks again.
6. Publish a focused correction.

Do not repeatedly make blind CI-only edits.

## 9. Device debugging

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

## 10. APK delivery

Deliver an APK only after the relevant build completes successfully.

For a new app, the first normal APK handoff requires the mandatory diagnostics baseline unless the user explicitly requested a temporary exception.

Record or surface enough build identity to trace the APK back to its source commit/workflow run when practical.
