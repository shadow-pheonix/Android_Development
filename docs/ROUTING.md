# Task Routing

Use this document after reading the root `AGENTS.md`.

## Environment rule before routing

For AI/assistant tasks in ChatGPT, **local means the chat-local workspace attached to the current chat/agent session**. It does not mean the user's Windows PC and does not authorize `windows-bridge`.

- Do not search for, connect to, or invoke `windows-bridge` merely to satisfy a “local” or “local-first” instruction.
- Use `windows-bridge` only when the user explicitly asks for it or explicitly asks to perform the current task on their Windows machine.
- If direct `git clone` is unavailable in the chat runtime, synchronize/materialize the repository through the authorized GitHub connector into the chat-local workspace, then work there.

## Decision tree

1. **Does the requested app already exist in this repository?**
   - **No** → Route A: New app.
   - **Yes** → Route B: Existing app.
   - **Task is not app-specific** → Route C: Shared/CI/docs/infrastructure.

2. **For an existing app, is there a current chat-local checkout/snapshot?**
   - **No** → sync the repository into the chat-local workspace before editing. Prefer `git clone`; if unavailable, use authorized GitHub connector/materialization.
   - **Yes** → fetch/sync the relevant branch/commit before editing.

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

Do not create the app piecemeal through GitHub connector writes. Build the intended batch in the chat-local working environment first.

Minimum sequence:

1. Sync the monorepo into the chat-local workspace.
2. Choose a unique app directory and application ID.
3. Create the complete app in the chat-local workspace.
4. Implement mandatory diagnostics export to `AppDebug/<AppName>/` according to [DIAGNOSTICS.md](DIAGNOSTICS.md).
5. Add app documentation and app-level `AGENTS.md` only when useful.
6. Run all validation available in the chat-local workspace.
7. Review the complete diff.
8. Commit/push the coherent batch.
9. Run the app-specific GitHub Actions workflow.
10. Inspect build/test results.
11. Produce the APK artifact.

## Route B — existing app

Never reconstruct an existing app from memory in a fresh chat.

Minimum sequence:

1. Read root `AGENTS.md`.
2. Sync the current GitHub repository into the chat-local workspace. Prefer clone/fetch when available; otherwise use authorized connector/materialization.
3. Locate the exact target app.
4. Read its README, app-level `AGENTS.md`, build files, and relevant source.
5. Inspect whether the mandatory diagnostics baseline exists; add it during active development if missing unless explicitly deferred.
6. Establish a baseline when the change is substantial or risky.
7. Implement in the chat-local workspace.
8. Validate in the chat-local workspace.
9. Review the diff.
10. Commit/push the coherent batch.
11. Run only the relevant CI workflow.
12. Produce the APK artifact after successful validation.

**Do not use the user's Windows checkout or `windows-bridge` for steps 2, 7, or 8 unless the user explicitly requested that environment.**

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

1. Sync current repository state into the chat-local workspace.
2. Identify affected apps/workflows.
3. Make the change in the chat-local workspace.
4. Run narrow validation there first.
5. Expand validation only when the shared impact requires it.
6. Review the final diff.
7. Commit/push.

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
