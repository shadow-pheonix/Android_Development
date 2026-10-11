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

- Add beside Breakfast, Lunch, Snacks or Dinner opens meal-specific Andhra food suggestions, with a separate background-loaded cache for each meal. Typed search still searches the entire offline catalogue.
- Refined daily targets with optional measured body fat, lab resting calories, observed stable-weight maintenance intake and training type. [Calculation rules and sources](TARGET_CALCULATIONS.md) explain input priority, macro budgets, automatic/custom targets and age-specific estimates. Optional fields do not trigger setup for returning users.
- Startup Google Drive sign-in restores existing backups before deciding whether personal-profile setup is needed. A valid saved profile opens Home directly; only missing/incomplete profiles need setup.
- Optional Google Drive sign-in or Continue without login after the launch animation, before first-run profile setup. The choice is remembered on this device and Drive can be connected later from Profile.
- Body recomposition goal with maintenance-calorie estimates, no planned weekly scale-weight change and higher recommended protein; actual activity level remains a separate calorie multiplier.
- A brief animated FuelNote launch screen, followed by guided profile setup for users without completed personal details. Setup covers age/DOB, sex, measurements, goal and activity, then previews calculated calorie and macro targets before saving.
- Personalized daily and seven-day meal plans, explicit food preferences, bounded diary/selection learning, local budgets/prices, recipes, pantry/leftovers and consolidated shopping. [Planner behaviour and limits](DIET_PLANNER.md).
- Today's calories, protein, carbs and fat.
- Illustrated Breakfast, Lunch, Snacks and Dinner headers with gentle offline vector animation; motion pauses when Home is hidden and respects disabled animations and battery saver.
- Tap the Home nutrition progress card for the selected day's vitamins and minerals, with profile-adjusted Indian targets and explicit source-data coverage.
- Manual logging by grams and per-100 g nutrition.
- Fully offline food search after installation; no runtime food API and no API keys.
- Bundled food photos in search, recent/frequent/favorite foods, diary entries and food details; official brand product images, representative package fallbacks and reviewed stock food photos with source credits.
- The centre Scan button opens the camera barcode scanner directly, preserving the selected diary date and meal. EAN-13, EAN-8, UPC-A and UPC-E use exact offline catalogue lookup; scanning is also available from Search.
- Numeric barcode lookup remains available through normal search.
- Tap a search result to populate the logger and view product/nutrition details.
- Profile places Edit profile and Log in / sign up side by side. Signed-in users see Manage account in the same spot; connection status and sync controls live on the account page.
- SQLite meal history with micronutrient snapshots for new logs and offline-catalog enrichment for older compatible entries.
- Optional Google Drive `appDataFolder` backup/sync: local-first writes, background upload/merge, per-device snapshots, deletion tombstones, and rolling recovery history.
- Optional Supabase cloud sync alongside Drive: passwordless email-code accounts, Android-Keystore-encrypted refresh sessions, RLS-protected per-user snapshots, background merge/upload, manual restore, and rolling recovery history.
- Mandatory bounded logs, crash capture and `Export diagnostics`.

Barcode decoding uses ZXing Core under the Apache License 2.0; camera capture uses Android Camera2 directly so scanning has no runtime network/service dependency.

## Startup and first-run setup

### Drive profile recovery (0.7.33)

Fresh installations wait for a foreground backup check/merge after Drive authorization,
including snapshots from this device ID. The restored completion marker and profile
validation decide whether setup is needed. Interrupted recovery resumes after Activity
recreation or restart; background Drive uploads wait until it finishes. Failed checks
offer retry or guest mode and do not classify the account as new. Choosing guest mode
after failure disconnects Drive locally without deleting the remote backup. Diagnostics
record recovery outcome and snapshot counts, without profile values or access tokens.

### Launch and personal details (0.7.29)

Fresh activity launches reveal the FuelNote mark and wordmark over the frosted scene
in about one second. The animation pauses in the background, is skipped for disabled
animations or battery saver, and does not replay on activity recreation or ordinary
foreground returns. Android 12+ uses the same mark for the system launch screen.

Users without a completed personal profile enter a four-step setup before Home:
personal details, height/current weight, goal/activity/target weight, and a target
preview. Required inputs begin empty on a new installation; name and DOB are optional
when age is entered. Each step validates its inputs, and the final confirmation saves
the profile plus recommended calorie and macro targets together. Back revisits steps
or leaves the app without marking setup complete. In-progress inputs and the current
step survive activity recreation. Export diagnostics remains available during setup.

The `personalProfileComplete` preference distinguishes confirmed personal details
from nutrition-only saves. Existing valid profiles saved before this version are
accepted without repeating setup; incomplete or invalid stored profiles require it.
No profile values are written during partial setup or logged by the new flow. All
details remain editable through Profile after setup.

## Optional login and recomposition (0.7.30)

After the animation, users who have not made an entry choice see Continue with Google
Drive and Continue without login. Successful Drive authorization or an explicit guest
choice advances to profile setup (or Home for an existing completed profile). Connected
Drive users skip this choice. Cancellation/failure does not count as a choice; users
can retry or continue offline. Guest remains available while authorization is preparing,
and withdrawing that request prevents a late consent screen or token storage. UI task
listeners are Activity-scoped, and returning from background/rotation releases stale
busy controls. Consent results still use the existing recreation-safe Activity result
handler. The choice is device-local and does not create another cloud account or change
Google's existing private `drive.appdata` scope. Diagnostics remains accessible.

Both goal menus show Lean bulk, Cut, Body recomposition and Dirty bulk first, followed by
Lose weight, Maintain weight, Gain weight and Custom (updated in 0.7.31). Stored
profiles and setup drafts use goal names, so ordering does not change a saved goal.

Body recomposition is a goal rather than an activity level. Mifflin–St Jeor BMR and the
user's actual activity multiplier produce estimated maintenance calories (rounded to
10 kcal, with the existing automatic 1,200 kcal floor). It requests zero weekly weight
change and keeps the initial weight target at current weight. Protein starts at 2 g/kg
of current body weight; fat starts at 0.8 g/kg or 20% of calories, whichever is greater;
carbohydrates fill the remaining energy. Energy-budget constraints cap recommended
protein at 40% of calories and reserve at least 50 g/20% of calories for carbs before
allocating fat, preventing recommendations from overshooting low manual targets or
high-weight profiles. Whole-gram rounding accounts for at most 2 kcal difference.

Setup generates these recommendations automatically. Switching to recomposition in
Profile applies them once; later custom macro edits and explicit calorie overrides
remain available. Weekly weight-rate controls and Progress use zero for this goal.
Targets are starting estimates; resistance training and measured trends guide later
adjustments. The 2 g/kg starting protein target is within the ISSN exercise protein
position stand's 1.4–2.0 g/kg/day range (DOI: 10.1186/s12970-017-0177-8).

Nine calculator regression tests cover reference BMR/TDEE examples, actual activity,
sex/DOB, stale weight-loss rates, recomposition protein, macro-budget edge cases,
manual overrides, existing goals and invalid non-finite values.

## Bundled food catalogue

### Suggestions by meal (0.7.35)

The All shortcut and empty search now use the selected meal rather than one shared
favourites list. Breakfast has 18 suggestions including pesarattu, idli, dosa/upma,
eggs, milk and chutneys. Lunch has 18 suggestions led by cooked rice, pappu, curries
and vegetable fries. Snacks has 16 suggestions including fruit, nuts, puffed rice,
eggs, milk and tea-time foods. Dinner has 18 suggestions led by chapati, rice, dal,
curd rice and curries, with khichdi and tiffin alternatives.

`tools/meal_foods.json` defines stable catalogue IDs and optional readable labels;
the builder writes an indexed `meal_foods` table and verifies that every production
suggestion resolves to complete INDB/IFCT/USDA nutrition. Cooked dishes cannot turn
into a packaged mix through keyword matching. Milk is explicitly whole cow milk;
boiled eggs and plain omelette use the corresponding USDA cooked records. Source
nutrition, serving data, food IDs and photo assignments remain unchanged. These
suggestions help choose foods to log; they are not a prescribed menu or portion plan.

Add buttons pass their meal through the existing logging context, and a captured
meal key controls both the query and delivery. Each meal keeps an immutable cache;
changing the meal refreshes rows even when the previous meal was already displayed.
Typing, selecting a history shortcut, or leaving Search invalidates older callbacks.
Revisiting the same suggestions reuses cached rows. Navigation still opens before
worker queries, and diagnostic logs contain meal keys, counts and durations only.
Search queries, Recent, Frequent, Favorites and barcode lookup remain unrestricted.

### Andhra favourites and search (0.7.32)

The default food list uses 16 reviewed INDB cooked dishes by stable catalogue code:
annam, palakura pappu, pesarattu, idli, dosa, rava upma, perugu annam, tamarind and
lemon pulihora, sambar, charu, egg/chicken/fish curry, bendakaya fry and garelu.
It no longer chooses the first result of an ambiguous keyword, which could be a
packaged mix. Regional display names and 31 Telugu/transliterated alias mappings
leave source nutrition, food IDs, barcodes and photo assignments unchanged.
Raw dal is labelled raw and remains distinct from cooked pappu. These are equivalent
catalogue dishes, not nutrition claims for every Andhra household's recipe.

Offline search now uses portable SQLite FTS4 with word-prefix matching in any order
across names, brands, aliases and categories. Exact dishes/local aliases rank first,
then name/brand matches, with category-only matches last. Complete nutrition and
Indian cooked-food sources break relevance ties. Brand words and preparation words
are retained; harmless connectors such as "and"/"with" are ignored and common food
plurals are recognized. Searching `dosa batter` does not turn into a cooked-dosa recommendation.
Barcodes still use indexed exact lookup and scanner-format normalization.

Common catalogue misspellings normalize to canonical names. When normal matching
returns nothing, an indexed spelling vocabulary supplies at most three close words
per token, using bounded edit/adjacent-transposition distance. These results are
labelled Close matches. English correction does not invent Telugu recipe mappings
or drop unmatched ingredients. Unknown recipes can still be added manually.

Results update 250 ms after typing stops; Search/keyboard Search runs immediately.
Clear restores Andhra favourites, and Recent/Frequent/Favorites remain separate.
Navigation retains a typed query. Obsolete requests are invalidated on text edits,
tab switches, navigation and backgrounding; pending work resumes when appropriate.
Catalogue queries run on the existing serial worker. Inputs, candidate counts and
corrections are bounded (128 characters, eight words, 600 candidates, 30 UI results).
Diagnostics record only query length, counts, approximate status and duration.

Validation includes nine builder/index tests, thirteen Java planner/ranking tests and
actual runtime SQL checks against the full catalogue. Representative local candidate
queries took under 2 ms in the workspace; this excludes phone rendering and is not
a device latency guarantee. Android CI supplies lint, unit tests and APK compilation.

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
shows the page before requesting foods. Suggestions are loaded once per meal per activity
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

Diary, Relog, date of birth and weight-log calendars now share medium dialog sizing:
320 dp in portrait and 480 dp in landscape, bounded by the available screen width
with side margins. Oversized native header text is capped at 22 sp, and dates and
weekdays use compact typography. Height follows the native content so rows and
confirmation controls remain accessible; the frosted theme and date limits are retained.

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
