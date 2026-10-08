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

## Offline food photos

The curated 8 October 2026 image bundle uses primary product images from official manufacturer pages and visually reviewed stock photos. It contains no Open Food Facts community photos or Wikimedia images. This changes image sources only, not the nutrition dataset notices above.

Manufacturer imagery remains brand-owned and is included for the user's requested personal offline use. Sources include Britannia, English Oven, iD Fresh, Milky Mist, Yoga Bar, True Elements, Happilo, Farmley, Epigamia, Nakpro, Nestlé Maggi, Orkla India/MTR and Cadbury. Brand/product matches are explicitly reviewed; pack size and regional design may differ. When a product's own brand image is unavailable, a clean package image of the same product type may represent it and is clearly labelled as such.

Pexels stock photographs illustrate cooked dishes and ingredient families under the Pexels License: https://www.pexels.com/license/. A Milky Mist studio paneer image also illustrates the paneer ingredient family under the brand-owned terms above. Preparation can differ from the representative photo.

`app/src/main/assets/food_photos.json` provides per-image credits, terms, source links, content hashes and modification notices. Images have been resized, metadata stripped and compressed as WebP. Food details expose Photo credits and links to the source and image terms. `tools/food_photo_sources.json` retains review notes and approved matching rules. These image terms do not change the licenses of the nutrition datasets above.

Acquisition and validation instructions: `tools/PHOTO_PIPELINE.md`.
