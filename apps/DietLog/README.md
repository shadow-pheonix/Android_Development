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
- Tap the Home nutrition progress card for the selected day's vitamins and minerals, with profile-adjusted Indian targets and explicit source-data coverage.
- Manual logging by grams and per-100 g nutrition.
- Fully offline food search after installation; no runtime food API and no API keys.
- Camera barcode scanning for EAN-13, EAN-8, UPC-A and UPC-E with exact offline catalogue lookup.
- Numeric barcode lookup remains available through normal search.
- Tap a search result to populate the logger and view product/nutrition details.
- SQLite meal history with micronutrient snapshots for new logs and offline-catalog enrichment for older compatible entries.
- Optional Google Drive `appDataFolder` backup/sync: local-first writes, background upload/merge, per-device snapshots, deletion tombstones, and rolling recovery history.
- Optional Supabase cloud sync alongside Drive: passwordless email-code accounts, Android-Keystore-encrypted refresh sessions, RLS-protected per-user snapshots, background merge/upload, manual restore, and rolling recovery history.
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

## Google Drive backup setup

Drive backup uses Google Identity Services authorization and the narrow `drive.appdata` scope. The Android distribution must be registered in a Google Cloud project with its package name and SHA-1 signing certificate, and the Google Drive API must be enabled for that project. No OAuth access token is persisted by DietLog.

Debug builds currently use the `.debug` application ID suffix, so OAuth registration for CI/debug APKs must match `com.shadowphoenix.dietlog.debug` and the SHA-1 of the signing certificate used for that APK. The exact certificate SHA-1 is included in `app-info.txt` inside an exported diagnostics ZIP. Authorization failures are logged with the Google status code so OAuth configuration errors are actionable.

## Supabase cloud sync setup

Supabase is an optional second cloud provider; Google Drive remains available independently. DietLog
uses Supabase Auth passwordless email sign-in and the Data API with a public publishable key. The user
enters an email address, receives a six-digit one-time code, and verifies that code in DietLog. No
password is created or stored by the app. The app never includes a `service_role`/secret key, encrypts
the refresh token at rest using Android Keystore AES-GCM, and keeps access tokens in memory only.

The backend schema and RLS policies are documented in `SUPABASE_BACKEND.md`. Apply them only to the
dedicated DietLog Supabase project. Each authenticated user can access only rows where
`user_id = auth.uid()`.

For numeric email codes, Supabase's **Magic Link** email template must include `{{ .Token }}` rather
than only `{{ .ConfirmationURL }}`. As of October 2026, new Free-plan Supabase projects using the
default SMTP service cannot customize auth email templates, so they send a magic link instead. Numeric
OTP delivery on those projects requires configuring custom SMTP (or another Supabase plan/configuration
that permits editing the auth template). This is a Supabase email-delivery limitation; DietLog does not
need GitHub, Google, another OAuth provider, or an Android signing SHA-1 for Supabase email-code auth.

Android builds receive the public project configuration from these environment variables:

```text
DIETLOG_SUPABASE_URL=https://<project-ref>.supabase.co
DIETLOG_SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
```

For GitHub Actions, configure repository **Variables** with those two names. The workflow never prints
their values. If either variable is absent, the APK still builds and all local/Google Drive features keep
working, while the Supabase Profile card clearly reports that cloud sync is not configured in that build.

## Build

CI downloads pinned datasets, builds and validates the offline database, then runs:

```bash
gradle --no-daemon lintDebug testDebugUnitTest assembleDebug
```
