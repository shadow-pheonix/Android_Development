# AGENTS.md

## Development Workflow

For Android development in this repository, use a local-first workflow before committing changes to GitHub.

1. Prepare, edit, and inspect code/scripts in the local working environment first whenever practical.
2. Run all locally available validation before committing, including syntax checks, structural checks, targeted tests, and static inspection.
3. Keep related changes grouped into coherent batches instead of committing partially completed or knowingly broken intermediate states.
4. Commit to GitHub only after the current batch is internally consistent and ready for authoritative build/test validation.
5. Use GitHub Actions as the authoritative Android build environment for compilation, lint, tests, APK generation, and other Android SDK-dependent validation.
6. If GitHub Actions fails, inspect the specific failing step/log, fix only what is necessary, validate locally where possible, and push a focused follow-up commit.
7. Avoid noisy trial-and-error commits when the same issue can be resolved locally first.
8. Preserve completed work and intermediate results between iterations; do not redo successful steps without a technical reason.
9. Prefer short, bounded, resumable build/test/debug cycles over long-running or exhaustive runs.
10. For substantial work, establish a working baseline first, then make and validate changes incrementally.

### Environment Constraint

The assistant's local environment may not always include a complete Android SDK, Android Studio, emulator, or device connection. In those cases, perform all meaningful local preparation and validation that is available, then rely on GitHub Actions for the final Android-specific build and test verification.

### Preferred Flow

**Prepare locally → validate locally → commit coherent batch → build/test in GitHub Actions → inspect failures if any → make focused fix → produce APK artifact.**
