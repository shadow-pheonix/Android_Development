# Offline food data notices

DietLog builds a local SQLite food catalogue at build time. The Android app performs no runtime food API calls.

## Open Food Facts (India packaged foods)

Product records are derived from Open Food Facts. Database rights are licensed under ODbL 1.0 and individual contents are subject to the Open Food Facts data/content terms. The build uses an India-filtered snapshot plus OpenLabel's ODbL-attributed India subset to enrich ingredient text where available.

Sources pinned by `.github/workflows/build-dietlog.yml`:
- `mvark/indiafoodstats` India Open Food Facts snapshot.
- `armaanux/openlabel` generated India Open Food Facts subset.

## IFCT 2017

Indian raw ingredient composition is sourced from the normalized IFCT extract published by `krishnabhat/indian-food-nutrition-mcp`, which documents the upstream `@nodef/ifct2017` extract as AGPL-3.0-or-later. This private project retains the source/build path and attribution.

## USDA SR Legacy

The normalized USDA SR Legacy extract is sourced from `krishnabhat/indian-food-nutrition-mcp`. USDA FoodData Central/SR Legacy data is U.S. government public-domain data.

## INDB cooked dishes — private build only

The Indian Nutrient Databank (Jaacks Lab) supplies 1,014 cooked Indian dishes and serving information. Its GitHub repository does not specify a redistribution license. DietLog therefore treats this layer as **personal/private-build only**. Do not distribute a public APK containing INDB until explicit redistribution permission is confirmed. The build fetches the authors' workbook directly and does not commit it to this repository.
