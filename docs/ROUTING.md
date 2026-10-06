# Task Routing

Use this document after reading the root `AGENTS.md`.

## Environment rule before routing

For AI/assistant tasks in ChatGPT, **local means the chat-local workspace attached to the current chat/agent session**. It does not mean the user's Windows PC and does not authorize `windows-bridge`.

For an existing repository/app, first inspect whether the required repository files already exist in the chat-local workspace. Reuse them when the local repository snapshot can be verified byte-for-byte against the latest intended GitHub commit. Request a ZIP only when the required local files are missing, incomplete, unverifiable, or mismatched.

- Do not search for, connect to, or invoke `windows-bridge` merely to satisfy a “local” or “local-first” instruction.
- Use `windows-bridge` only when the user explicitly asks for it or explicitly asks to perform the current task on their Windows machine.
- Do not use `git clone` as the normal source-ingest path for ChatGPT development.
- Do not reconstruct the repository file-by-file through the GitHub connector, download individual files as a substitute, or try alternate import methods merely to avoid requesting a required ZIP.
- If the existing chat-local snapshot matches the latest intended commit byte-for-byte, record that commit SHA and use the existing files without asking for another ZIP.
- If a ZIP is required, ask the user for the exact latest-commit ZIP and include the direct archive link `https://github.com/shadow-pheonix/Android_Development/archive/<COMMIT_SHA>.zip` in the same message; then extract it into the chat-local workspace and record its base commit SHA.
- Verify the intended GitHub branch still matches the recorded base commit before editing and again before publishing.
- Use the GitHub connector/API for repository-state verification, final publication, CI inspection, and artifact retrieval.

## Decision tree

1. **Does the requested app already exist in this repository?**
   - **No** → Route A: New app.
   - **Yes** → Route B: Existing app.
   - **Task is not app-specific** → Route C: Shared/CI/docs/infrastructure.

2. **Are the required repository files already present in the chat-local workspace, and do they match the latest intended GitHub commit byte-for-byte?**
   - **Yes** → record that commit SHA, reuse the existing files, and do not request another ZIP.
   - **No / missing / incomplete / unverifiable** → ask the user to upload a ZIP of the exact latest intended commit, include its direct GitHub archive link in the same message, then extract it into the chat-local workspace before editing.

3. **Does the target app contain its own `AGENTS.md`?**
   - **Yes** → read it after the root `AGENTS.md` and before editing.
   - **No** → root instructions govern the workflow.

4. **Does the app have the mandatory diagnostics baseline?**
   - **New app** → implement it before first normal APK handoff.
   - **Existing app without it** → add it during active development unless the user explicitly defers it.
   - See [DIAGNOSTICS.md](DIAGNOSTICS.md).

5. **How heavy is the task?**
   - **Very light / light** → no checkpoint feature branch is required; complete, validate, and publish as one coherent direct batch.
   - **Anything heavier than light** → plan checkpoints proportional to task weight, create a dedicated feature branch from the verified base, publish each coherent validated checkpoint there, keep `main` unchanged during implementation, and merge only after all checkpoints and final validation are complete.

6. **Which CI workflow should run?**
   - App-only change → only that app's workflow.
   - Shared dependency change → affected apps only.
   - Docs-only change → no Android build by default.
   - Workflow change → run the workflow(s) being modified or otherwise affected.
   - Checkpointed work → prefer final authoritative CI on the feature branch before merging when supported.

## Route A — new app

Target path: `apps/<AppName>/`.

Do not create the app piecemeal through GitHub connector writes. Start from an existing chat-local monorepo snapshot only when it matches the latest intended GitHub commit byte-for-byte; otherwise request the exact latest-commit ZIP with its direct archive link and build the intended batch in the extracted chat-local working environment first.

Minimum sequence:

1. Read root `AGENTS.md`.
2. Inspect for an existing chat-local monorepo snapshot and verify it byte-for-byte against the latest intended GitHub commit.
3. Reuse it if it matches; otherwise request the exact latest-commit ZIP from the user with its direct archive link, extract it, and record/verify the base commit SHA.
4. Choose a unique app directory and application ID.
5. Create the complete app in the chat-local workspace.
6. Implement mandatory diagnostics export to `AppDebug/<AppName>/` according to [DIAGNOSTICS.md](DIAGNOSTICS.md).
7. Add app documentation and app-level `AGENTS.md` when useful.
8. Classify task weight. If heavier than light, plan checkpoints and create a dedicated feature branch from the verified base.
9. Implement and validate locally; for checkpointed work, publish each coherent validated checkpoint to the feature branch.
10. Review the complete cumulative diff.
11. Verify the expected base/`main` lineage is still valid.
12. For a light task, publish one coherent commit; for checkpointed work, run final branch validation/CI and merge the feature branch to `main` only after all checkpoints are complete and verified.
13. Run/confirm the app-specific GitHub Actions workflow.
14. Inspect build/test results.
15. Produce the APK artifact.

## Route B — existing app

Never reconstruct an existing app from memory in a fresh chat.

Minimum sequence:

1. Read root `AGENTS.md`.
2. Inspect for an existing chat-local repository snapshot and verify it byte-for-byte against the latest intended GitHub commit.
3. Reuse it if it matches; otherwise request the exact latest-commit ZIP from the user with its direct archive link, extract it into the chat-local workspace, and record the base commit SHA. Verify the intended GitHub branch still matches that SHA before editing.
4. Locate the exact target app.
5. Read its README, app-level `AGENTS.md`, build files, and relevant source.
6. Inspect whether the mandatory diagnostics baseline exists; add it during active development if missing unless explicitly deferred.
7. Establish a baseline when the change is substantial or risky.
8. Classify task weight. If heavier than light, plan checkpoints and create a dedicated feature branch from the verified base.
9. Implement and validate in the chat-local workspace; publish each coherent validated checkpoint to the feature branch when checkpointing applies.
10. Review the cumulative diff.
11. Verify the expected base/`main` lineage is still valid.
12. For a light task, publish the coherent batch directly; for checkpointed work, complete all checkpoints, run final validation/CI, then merge the feature branch to `main`.
13. Run/confirm only the relevant CI workflow.
14. Produce the APK artifact after successful validation.

**Do not use the user's Windows checkout or `windows-bridge` for steps 2, 3, 8, or 9 unless the user explicitly requested that environment.**

### Legacy root project

The repository currently contains a root-level Android test project from before the `apps/` layout was adopted.

Treat it as an existing app for routing purposes. Do not move it under `apps/` unless migration is explicitly requested.

## Route C — shared, CI, docs, infrastructure

Examples:

- `shared/**`
- `.github/workflows/**`
- `README.md`, `AGENTS.md`, `docs/**`
- repository-wide Gradle/build tooling

Minimum sequence:

1. Inspect for an existing chat-local repository snapshot and verify it byte-for-byte against the latest intended GitHub commit.
2. Reuse it if it matches; otherwise request the exact latest-commit ZIP from the user with its direct archive link, extract it into the chat-local workspace, and record/verify the base commit SHA.
3. Identify affected apps/workflows.
4. Classify task weight. If heavier than light, plan checkpoints and create a dedicated feature branch from the verified base.
5. Make the change in the chat-local workspace; for checkpointed work, publish each coherent validated checkpoint to the feature branch.
6. Run narrow validation there first.
7. Expand validation only when the shared impact requires it.
8. Review the final cumulative diff.
9. Verify the expected base/`main` lineage is still valid.
10. For a light task, publish the coherent change directly; for checkpointed work, merge the feature branch to `main` only after all checkpoints and final validation are complete.

## CI routing convention

For app-specific workflows, prefer names such as:

- `.github/workflows/build-<app-name>.yml`

and path filters such as:

```yaml
on:
  pull_request:
    paths:
      - "apps/ExampleApp/**"
      - ".github/workflows/build-example-app.yml"
```

Add shared paths only when that app genuinely depends on them.

Do not add broad `apps/**` filters to every app workflow, because that defeats isolation.
