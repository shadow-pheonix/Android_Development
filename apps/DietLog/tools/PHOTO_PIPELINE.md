# Offline food photo pipeline

The committed `app/src/main/assets/food_photos.json` is the source/credit manifest for offline WebP thumbnails. The reviewed source registry is `food_photo_sources.json` (8 October 2026). Image changes do not modify nutrition or the database schema.

- First choice for packaged foods: primary product imagery from a reviewed official manufacturer page. Explicit catalogue codes **and** approved brand aliases must both match. Package size or regional design can differ; this is not a claim that the brand page published that exact barcode.
- If an official match is unavailable, a reviewed clean package image of the same product type can be used, including another brand's official packshot. It remains **representative**, with a note that brand and packaging can differ. Clean stock ingredient photos are a secondary fallback. Missing or ambiguous food types keep the food symbol.
- Cooked dishes and ingredients use visually reviewed Pexels stock photographs or a manufacturer's studio ingredient photograph. Community Open Food Facts photos, customer/review images and Wikimedia snapshots are not included in the curated bundle.
- Whole-phrase aliases, preparation requirements, exclusions and source scopes prevent a flavour or ingredient word from selecting the wrong food. USDA/IFCT family matching uses the food name before the first comma, while preparation checks use the full name. A handful of reviewed abbreviated packaged-food names have explicit representative `fallback_codes`; these do not become official matches.
- Every registry entry records its source page, original URL, credit, terms, visual review note and the SHA-256 of the **reviewed compressed thumbnail**. The builder rejects changed image bytes instead of silently accepting a replacement at the same URL. Review new bytes and update the hash before rebuilding.
- Brand imagery remains brand-owned; personal offline use does not make it openly licensed. Pexels images retain the Pexels License. The per-image manifest preserves the applicable terms and source links. Thumbnails are at most 256 px, with metadata removed and WebP compression.
- Downloads are bounded, cached and resumable, with reviewed HTTPS hosts and redirects. All wanted images are staged and verified before changing the existing bundle. A failed download or changed review hash leaves the previous bundle intact. There are no runtime image requests.

Rebuild using the same catalogue database as the Android build, Python and Pillow:

```bash
python tools/curated_food_photos.py --food-db app/src/main/assets/offline_foods.db --sources tools/food_photo_sources.json --cache /tmp/curated-photo-cache --assets app/src/main/assets --workers 4
python tools/validate_food_photos.py --food-db app/src/main/assets/offline_foods.db --assets app/src/main/assets
python -m unittest tools.test_photos -v
```

Keep downloads, caches and contact sheets outside the repository. Add manufacturers to the fixed reviewed host lists only after verifying the product-page source and reviewing the actual primary image. Never use search thumbnails, access-gated sources or customer uploads as a substitute. CI recomputes assignments against the pinned catalogue and verifies metadata, hashes, dimensions, coverage and orphan assets; it does not scrape live websites.

The old OFF/Commons acquisition scripts and schema-1 tests remain for historical reproducibility. They are not the current acquisition path. Schema-2 validation only accepts the reviewed official/stock registry.
