# DietLog

Offline-first Android diet logger focused on Indian foods.

## Current functionality

- Today's calories, protein, carbs and fat.
- Manual logging by grams and per-100 g nutrition.
- Fully offline food search after installation; no runtime food API and no API keys.
- Numeric barcode lookup against the bundled India packaged-food catalogue.
- Tap a search result to populate the logger and view product/nutrition details.
- SQLite meal history.
- Mandatory bounded logs, crash capture and `Export diagnostics`.

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
