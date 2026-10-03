# Task Routing

Use this document after reading the root `AGENTS.md`.

## Decision tree

1. **Does the requested app already exist in this repository?**
   - **No** → Route A: New app.
   - **Yes** → Route B: Existing app.
   - **Task is not app-specific** → Route C: Shared/CI/docs/infrastructure.

2. **For an existing app, is there a current local checkout?**
   - **No** → clone the repository locally before editing.
   - **Yes** → fetch/sync the relevant branch/commit before editing.

3. **Does the target app contain its own `AGENTS.md`?**
   - **Yes** → read it after the root `AGENTS.md` and before editing.
   - **No** → root instructions govern the workflow.

4. **Which CI workflow should run?**
   - App-only change → only that app's workflow.
   - Shared dependency change → affected apps only.
   - Docs-only change → no Android build by default.
   - Workflow change → run the workflow(s) being modified or otherwise affected.

## Route A — new app

Target path: `apps/<AppName>/`.

Do not create the app piecemeal through GitHub connector writes. Build the intended batch in the local working environment first.

Minimum sequence:

1. Sync the monorepo locally.
2. Choose a unique app directory and application ID.
3. Create the complete app locally.
4. Add app documentation and app-level `AGENTS.md` only when useful.
5. Run all available local validation.
6. Review the complete diff.
7. Commit/push the coherent batch.
8. Run the app-specific GitHub Actions workflow.
9. Inspect build/test results.
10. Produce the APK artifact.

## Route B — existing app

Never reconstruct an existing app from memory in a fresh chat.

Minimum sequence:

1. Read root `AGENTS.md`.
2. Clone or sync the current GitHub repository locally.
3. Locate the exact target app.
4. Read its README, app-level `AGENTS.md`, build files, and relevant source.
5. Establish a baseline when the change is substantial or risky.
6. Implement locally.
7. Validate locally.
8. Review the diff.
9. Commit/push the coherent batch.
10. Run only the relevant CI workflow.
11. Produce the APK artifact after successful validation.

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

1. Sync current repository state locally.
2. Identify affected apps/workflows.
3. Make the change locally.
4. Run narrow validation first.
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
