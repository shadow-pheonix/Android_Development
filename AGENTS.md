# AGENTS.md

## Mandatory Development Workflow

This policy is **mandatory for all agents, assistants, contributors, and automated development work in this repository**.

Before making any source-code, build-script, workflow, configuration, or project change, you **MUST read and follow this entire `AGENTS.md` file**.

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

**Read AGENTS.md → sync exact target upstream/source → develop locally → validate locally → inspect final diff → commit/push coherent change → run GitHub Actions → inspect any CI-specific failure → fix locally → push focused correction → produce APK/artifact.**

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
- no temporary/debug-only material remains unless intentionally part of diagnostics;
- no secrets or unrelated changes are present;
- the change is ready for authoritative CI validation.

If any of these conditions are not satisfied, continue working locally instead of pushing an intermediate experiment.
