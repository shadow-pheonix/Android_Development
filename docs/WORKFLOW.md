# Development Workflow

This document expands the mandatory local-first rules in `AGENTS.md`.

## 1. Sync first

Before modifying an existing project, work from current repository state.

- No local checkout: clone the repository.
- Existing checkout: fetch/sync the intended branch or commit.
- Confirm the target app path before editing.

## 2. Establish scope and baseline

For small changes, inspect the relevant files and current behavior.

For substantial, risky, or performance-sensitive changes, establish a known-good baseline first. Keep the baseline narrow and reproducible.

## 3. Develop locally

Make source, resource, script, workflow, and configuration changes in the local working copy.

Prefer a small number of coherent changes over a large matrix of speculative experiments.

Do not use GitHub commits as scratch storage or CI as a syntax checker when the issue can be resolved locally.

## 4. Validate locally

Run the narrowest useful checks available, for example:

- syntax/format checks;
- targeted unit tests;
- static inspection;
- Gradle configuration checks when available;
- manifest/resource inspection;
- script validation;
- focused diff review.

The local environment may not contain the full Android SDK, emulator, NDK, or a physical device. That does not remove the local-first requirement; perform every meaningful check that is available locally.

## 5. Review the final diff

Before pushing, verify:

- only intended files changed;
- no temporary debugging files remain unless intentionally part of diagnostics;
- no secrets/signing material are present;
- no unrelated apps were modified;
- generated artifacts that should not be committed are absent;
- app/version/build changes are deliberate.

## 6. Commit and push

Commit only when the current batch is internally complete and locally verified.

Prefer focused commits with clear messages. Avoid knowingly broken or placeholder commits.

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
3. Reproduce or reason about it locally where possible.
4. Fix it in the local checkout.
5. Run local checks again.
6. Push a focused correction.

Do not repeatedly make blind CI-only edits.

## 9. Device debugging

For bugs that require a physical device:

- use app-integrated diagnostics/log export when available;
- capture crash reports, relevant device/app build info, and reproducible steps;
- screen recordings are useful for timing, UI, media, or interaction bugs;
- if a connected device/ADB bridge is explicitly available, device logs may be used directly.

Keep diagnostics bounded and avoid logging credentials, tokens, or sensitive user content.

## 10. APK delivery

Deliver an APK only after the relevant build completes successfully.

Record or surface enough build identity to trace the APK back to its source commit/workflow run when practical.
