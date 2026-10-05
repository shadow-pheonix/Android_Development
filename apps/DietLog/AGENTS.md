# DietLog agent instructions

Read the repository-root `AGENTS.md` first. All root workflow, diagnostics, safety, and publishing rules remain mandatory.

## Mandatory source handoff for ChatGPT work

For DietLog work, when there is no trusted current chat-local snapshot:

1. **The user must upload a ZIP of the latest intended `Android_Development` GitHub commit into the chat.**
2. Extract that ZIP into the chat-local workspace and use `apps/DietLog/` from the extracted tree as the working source.
3. Record the ZIP's base commit SHA and verify the intended GitHub branch still points to that commit before editing and again before publishing.
4. If the branch has advanced, request a fresh latest-commit ZIP before continuing.
5. Do not use `git clone`, GitHub file-by-file reconstruction, the user's Windows checkout, or `windows-bridge` as the normal source-ingest path.
6. `windows-bridge` remains opt-in only and may be used only when the user explicitly requests it for the current task.
7. The GitHub connector/API may verify commit state and publish the finalized local diff after local development, validation, and diff review are complete.

## DietLog-specific reminders

- Preserve the existing offline-first architecture unless the user explicitly changes that requirement.
- Preserve the existing dark neon-teal/glassmorphism visual language unless a redesign is explicitly requested.
- Keep the mandatory diagnostics export working and scoped to `AppDebug/DietLog/`.
- Make app changes in `apps/DietLog/` and avoid unrelated monorepo edits.
- Use short, bounded validation cycles and only publish coherent, reviewed changes.
