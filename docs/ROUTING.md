# Task Routing

Use this document after reading the root `AGENTS.md`.

## Environment rule before routing

For AI/assistant tasks in ChatGPT, **local means the chat-local workspace attached to the current chat/agent session**. It does not mean the user's Windows PC and does not authorize `windows-bridge`.

For an existing repository/app, the normal ChatGPT source handoff is a **ZIP uploaded by the user containing the latest GitHub commit**.

- Do not search for, connect to, or invoke `windows-bridge` merely to satisfy a “local” or “local-first” instruction.
- Use `windows-bridge` only when the user explicitly asks for it or explicitly asks to perform the current task on their Windows machine.
- Do not use `git clone` as the normal source-ingest path for ChatGPT development.
- Do not reconstruct the repository file-by-file through the GitHub connector when the user can provide the latest-commit ZIP.
- Extract the user's ZIP into the chat-local workspace, record its base commit SHA, and verify the intended GitHub branch still matches that commit before editing/publishing.
- Use the GitHub connector/API for repository-state verification, final publication, CI inspection, and artifact retrieval.

## Decision tree

1. **Does the requested app already exist in this repository?**
   - **No** → Route A: New app.
   - **Yes** → Route B: Existing app.
   - **Task is not app-specific** → Route C: Shared/CI/docs/infrastructure.

2. **Is there a trusted ZIP-derived chat-local repository snapshot for the intended GitHub commit?**
   - **No** → ask the user to upload a ZIP of the latest intended commit, then extract it into the chat-local workspace before editing.
   - **Yes** → verify its recorded base commit still matches the intended GitHub branch. If the branch advanced, request a fresh latest-commit ZIP.

3. **Does the target app contain its own `AGENTS.md`?**
   - **Yes** → read it after the root `AGENTS.md` and before editing.
   - **No** → root instructions govern the workflow.

4. **Does the app have the mandatory diagnostics baseline?**
   - **New app** → implement it before first normal APK handoff.
   - **Existing app without it** → add it during active development unless the user explicitly defers it.
   - See [DIAGNOSTICS.md](DIAGNOSTICS.md).

5. **Which CI workflow should run?**
   - App-only change → only that app's workflow.
   - Shared dependency change → affected apps only.
   - Docs-only change → no Android build by default.
   - Workflow change → run the workflow(s) being modified or otherwise affected.

## Route A — new app

Target path: `apps/<AppName>/`.

Do not create the app piecemeal through GitHub connector writes. Start from the user's latest-commit monorepo ZIP and build the intended batch in the extracted chat-local working environment first.

Minimum sequence:

1. Read root `AGENTS.md`.
2. Obtain the latest-commit monorepo ZIP from the user.
3. Extract it into the chat-local workspace and record/verify the base commit SHA.
4. Choose a unique app directory and application ID.
5. Create the complete app in the chat-local workspace.
6. Implement mandatory diagnostics export to `AppDebug/<AppName>/` according to [DIAGNOSTICS.md](DIAGNOSTICS.md).
7. Add app documentation and app-level `AGENTS.md` when useful.
8. Run all validation available in the chat-local workspace.
9. Review the complete diff.
10. Verify the GitHub branch still points to the recorded base commit.
11. Publish one coherent commit.
12. Run the app-specific GitHub Actions workflow.
13. Inspect build/test results.
14. Produce the APK artifact.

## Route B — existing app

Never reconstruct an existing app from memory in a fresh chat.

Minimum sequence:

1. Read root `AGENTS.md`.
2. Obtain from the user a ZIP of the latest intended GitHub commit.
3. Extract it into the chat-local workspace; record the base commit SHA and verify the intended GitHub branch still matches it.
4. Locate the exact target app.
5. Read its README, app-level `AGENTS.md`, build files, and relevant source.
6. Inspect whether the mandatory diagnostics baseline exists; add it during active development if missing unless explicitly deferred.
7. Establish a baseline when the change is substantial or risky.
8. Implement in the chat-local workspace.
9. Validate in the chat-local workspace.
10. Review the diff.
11. Verify the GitHub branch still points to the recorded base commit.
12. Publish the coherent batch.
13. Run only the relevant CI workflow.
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

1. Obtain the latest-commit repository ZIP from the user if no trusted current chat-local snapshot exists.
2. Extract it into the chat-local workspace and record/verify the base commit SHA.
3. Identify affected apps/workflows.
4. Make the change in the chat-local workspace.
5. Run narrow validation there first.
6. Expand validation only when the shared impact requires it.
7. Review the final diff.
8. Verify the GitHub branch still points to the recorded base commit.
9. Publish the coherent change.

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
