# DietLog

Offline-first Android diet logger focused on Indian foods.

## ChatGPT development handoff

DietLog follows the root monorepo rules in `AGENTS.md`, `docs/ROUTING.md`, and `docs/WORKFLOW.md`.

For DietLog development in a fresh chat, or whenever the current chat-local snapshot is stale or missing:

1. **The user uploads a ZIP of the latest `Android_Development` GitHub commit.**
2. The agent extracts that ZIP into the chat-local workspace and works from `apps/DietLog/` inside the extracted tree.
3. The exact base commit SHA for the ZIP is recorded and verified against the intended GitHub branch before editing/publishing.
4. All DietLog implementation, local validation, and diff review happen in that extracted chat-local workspace.
5. The GitHub connector/API is used only to verify repository state, publish the finalized local diff, inspect CI, and retrieve artifacts.

**Do not `git clone` the repository as the normal ChatGPT source-ingest path. Do not reconstruct DietLog from GitHub file-by-file. Do not use the user's Windows checkout or `windows-bridge` unless the user explicitly requests it for the current task.**

If GitHub has advanced beyond the ZIP's base commit, request a fresh latest-commit ZIP from the user before continuing development.

See `apps/DietLog/AGENTS.md` for the app-specific mandatory handoff reminder.

## Current functionality

- Today's calories, protein, carbs and fat.
- Manual logging by grams and per-100 g nutrition.
- Fully offline food search after installation; no runtime food API and no API keys.
- Camera barcode scanning for EAN-13, EAN-8, UPC-A and UPC-E with exact offline catalogue lookup.
- Numeric barcode lookup remains available through normal search.
- Tap a search result to populate the logger and view product/nutrition details.
- SQLite meal history.
- Mandatory bounded logs, crash capture and `Export diagnostics`.

Barcode decoding uses ZXing Core under the Apache License 2.0; camera capture uses Android Camera2 directly so scanning has no runtime network/service dependency.

## Bundled food catalogue

The build pipeline generates `app/src/main/assets/offline_foods.db` before Android compilation from pinned source snapshots:

1. Open Food Facts India snapshot — 18,613 packaged products in the pinned July 2026 dataset.
2. OpenLabel's India OFF subset — ingredient/category enrichment for overlapping barcodes.
3. IFCT 2017 — 542 Indian raw ingredients.
4. USDA SR Legacy — 7,793 general foods/drinks.
5. INDB — 1,014 cooked Indian dishes for this private/personal build.

The exact de-duplicated row count is printed and validated during CI. See `NOTICE_DATA.md` for licensing/provenance.

The SQLite schema preserves the useful structured fields (barcode, brand, categories, serving, macros, sugar, fibre, saturated fat, salt, Nutri-Score, NOVA, ingredients, etc.) plus `extra_json` containing the complete source row available to the builder.

## Rebuilding the database

`tools/build_food_db.py` accepts local source files and produces the SQLite asset. It uses Python stdlib plus `openpyxl` only for INDB `.xlsx` parsing. Fixtures and `tools/test_builder.py` validate the transform locally without network access.

## Build

CI downloads pinned datasets, builds and validates the offline database, then runs:

```bash
gradle --no-daemon lintDebug testDebugUnitTest assembleDebug
```
