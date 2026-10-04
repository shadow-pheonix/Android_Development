# DietLog

Android diet logging app focused on fast calorie/macro logging with an Indian-food-first data strategy.

## Current baseline

- Manual food logging using per-100 g calories, protein, carbs and fat.
- Automatic scaling to the consumed gram amount.
- SQLite persistence and today's totals/history.
- Provider abstraction ready for remote food search.
- Mandatory bounded logs, crash capture, and `Export diagnostics`.
- Diagnostics first attempt `Internal storage/AppDebug/DietLog/`; if Android scoped storage rejects a root-level generic ZIP, the exporter falls back to `Internal storage/Download/AppDebug/DietLog/` without requesting all-files access.

## Indian food data strategy

### FatSecret

Use the official FatSecret Platform API only through a server-side proxy. Never embed the FatSecret client secret in the APK. The proxy should obtain OAuth 2.0 client-credentials tokens, search with the India region enabled, and return normalized food/serving results to this app.

The client data model keeps provider `food_id` and `serving_id` separately because FatSecret explicitly permits those identifiers to be retained indefinitely while most returned food/nutrition content is subject to its current storage/refresh terms.

### Supplemental sources

- IFCT 2017 is appropriate for authoritative Indian raw ingredient composition.
- A barcode/open-food database can supplement packaged foods where licensing permits.
- User-created foods/recipes remain local and editable.

The intended search ranking is: exact recent/user foods → Indian packaged result → Indian generic/composite result → IFCT raw ingredient → manual creation.

## Build

From this directory with Java 17, Android SDK 35 and Gradle 8.9 available:

```bash
gradle --no-daemon lintDebug testDebugUnitTest assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```
