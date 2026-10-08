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
- Illustrated Breakfast, Lunch, Snacks and Dinner headers with gentle offline vector animation; motion pauses when Home is hidden and respects disabled animations and battery saver.
- Tap the Home nutrition progress card for the selected day's vitamins and minerals, with profile-adjusted Indian targets and explicit source-data coverage.
- Manual logging by grams and per-100 g nutrition.
- Fully offline food search after installation; no runtime food API and no API keys.
- Bundled food photos in search, recent/frequent/favorite foods, diary entries and food details; official brand product images, representative package fallbacks and reviewed stock food photos with source credits.
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

## Food photos

The 8 October 2026 curated snapshot covers **12,153 of 28,102 foods (43.2%)**: 230 approved official brand/product matches, 8,811 representative packaged-food matches and 3,112 representative ingredient/dish matches. It bundles 379 distinct images totaling about 2.3 MB. Lower coverage than the previous community-photo bundle is intentional: ambiguous products keep a food symbol instead of an unsuitable photo.

Packaged foods prefer reviewed primary images from official brand product pages. When the product's own brand image is unavailable, a clean image of the same product type may come from another brand and is labelled representative. Cooked foods use visually reviewed Pexels stock photos. Open Food Facts community photos and Wikimedia snapshots have been removed from the image bundle; the nutrition catalogue still uses the same datasets.

Photos are committed as compressed offline WebP assets, with per-image source and terms in `app/src/main/assets/food_photos.json`. Food details provide Photo credits, explain representative images, and link to the source. Foods without a usable match retain their food symbol. The manifest is streamed; loading and decoding run in the background with an 8 MiB bitmap cache. Both official and representative packages fit within their thumbnails. Nutrition values and logging behavior are unchanged.

See `tools/PHOTO_PIPELINE.md` for acquisition/rebuild instructions and `NOTICE_DATA.md` for terms. CI recomputes official-first matching rules and validates reviewed image hashes, dimensions, catalogue links and source metadata. It does not scrape images during a build.

## Google Drive backup setup

Drive backup uses Google Identity Services authorization and the narrow `drive.appdata` scope. The Android distribution must be registered in a Google Cloud project with its package name and SHA-1 signing certificate, and the Google Drive API must be enabled for that project. No OAuth access token is persisted by DietLog.

Debug builds use the `.debug` application ID suffix. CI builds are signed with one persistent DietLog debug certificate supplied through the `DIETLOG_ANDROID_SIGNING_JSON` GitHub Actions secret; the private keystore is never committed to the repository. The workflow verifies the produced APK certificate before uploading or publishing and fails if the configured signing material is missing or mismatched.

Register the CI/debug distribution in Google Cloud as an Android OAuth client with package `com.shadowphoenix.dietlog.debug` and SHA-1 `20:1C:00:86:46:F8:62:BE:78:89:82:B8:80:A6:AB:93:8F:C9:67:18`. The same SHA-1 is included in `app-info.txt` inside an exported diagnostics ZIP and in the rolling GitHub release notes. Authorization failures are logged with the Google status code so OAuth configuration errors are actionable.

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

### UI polish (0.7.13)

Home, Search, Progress and Profile share clearer spacing, brighter secondary text,
vector navigation icons with an active-tab background, and ripple feedback.
Food results show calories above a separate, wrapping macro line; meal headers keep
their animated artwork. Profile actions have their own row and calorie tiles place
the unit below the value. Progress charts use a subtle area fill for single-series
trends and leave gaps for missing data. Existing calendar selection, profile editor,
offline photos, nutrition calculations and diagnostics export remain available.

### Frosted glass and motion (0.7.14)

The app uses a dark, softly lit backdrop with smooth translucent frost, rounded
surfaces and directional edge highlights. The floating navigation bar captures only
the content behind the bar: Android 12+ uses hardware backdrop blur, while Android
10/11 blur a small quarter-resolution crop at a bounded refresh rate. Labels, food
photos and controls remain sharp. Scroll padding keeps the final items above the bar.

Page content enters with a short stagger, controls respond with a gentle press and
release, and calorie/macro rings and progress charts animate to their displayed values.
View and indicator motion stops when the app is paused or battery saver is enabled, respects Android's
animation setting, and restores the final state when cancelled or detached. Meal
illustrations share that policy. There are no network effects or animation libraries.

In 0.7.15, the grain texture is removed from all glass surfaces, including cards,
controls and navigation, for a smooth finish. Backdrop blur, edge highlights and
animations are retained.

### Sliding navigation selection (0.7.16)

One rounded frosted pill slides behind the selected Home, Search, Progress or Profile
tab over 320 ms. The active icon and label use the teal accent; labels are bold when
selected. Rapid taps retarget the pill from its current position. Initial layout,
size/RTL changes and disabled motion place it directly on the selected tab. Pausing,
hiding or detaching navigation settles the pill and cancels its animator. The center
Add button remains a separate action, and tab ripple and accessibility selection
feedback are preserved.

### Search responsiveness and gentler navigation (0.7.17)

The selection pill now uses a 450 ms slide with a gentler start. Opening Search
shows the page before requesting foods. Popular foods are loaded once per activity
on a serial database worker and cached, so returning to Search reuses both the
cached foods and the existing popular rows. Typed searches also run on that worker.
Loading feedback appears while results are pending. New requests, history shortcuts
and leaving Search suppress older results. Returning after leaving a pending search
restarts it; destroying the activity closes the
catalogue after an in-flight query finishes. Diagnostics record query duration and
result counts without recording the search text.

### Bottom-button feedback (0.7.19)

The five bottom buttons no longer shrink and spring back when pressed. Page fade
and entrance animations, the 450 ms sliding selection pill and native click feedback
remain available. Other buttons keep their existing press feedback.

### Consistent calendars (0.7.20)

Diary and Relog date selection share the rounded dark glass calendar theme. Its
opaque gradient keeps the diary behind the dialog from showing through, and the
native gray header is replaced with a transparent header over the same surface.
Dates, weekdays and the active header use the app's text colors with a teal selected
day. The theme is also the default for other app date pickers. Relog remains limited
to days before today; confirmation and cancellation behavior are unchanged.

### Home summary and date alignment (0.7.21)

The summary is titled “Daily nutrition” to cover calories and all three macros.
Both date arrows use the same 48 × 48 dp frosted control with centered, mirrored
vector chevrons. The date row centers controls vertically instead of aligning
their different text baselines.

### Consistent dropdowns (0.7.22)

Sex, activity level, calorie goal and macro input mode share one rounded frosted
menu with an opaque gradient, a subtle rim and inset options. The current choice
has a teal checkmark and highlight. Closed fields show a matching chevron, and
menu width, typography and touch spacing are consistent. Native Spinner selection,
scrolling and keyboard/accessibility behavior are retained.

### Navigation without tap effects (0.7.23)

Bottom navigation no longer draws native tap ripples over the sliding selection
pill. The center Add button also has no ripple or pressed rim highlight. Combined
with the existing removal of press scaling, the selection pill is now the sole
animated feedback on the bar. Its 450 ms slide, active icon/label colors, page fade,
click handlers and accessibility selection behavior are retained.
