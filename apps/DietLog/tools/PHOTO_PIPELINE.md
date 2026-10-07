# Offline food photo pipeline

The committed `app/src/main/assets/food_photos.json` is the source/credit manifest for the committed WebP thumbnails. Acquisition snapshot: 7 October 2026. It maps catalogue codes to attributed photos without modifying nutrition or the database schema.

- Packaged products use selected front photos from Open Food Facts' official bulk export, matched to exact barcodes including leading zeros. Product images are CC BY-SA 3.0.
- Ingredient and dish photos come from Wikimedia Commons. `photo_queries.json` contains reviewed file titles and whole-phrase food aliases scoped to catalogue sources. These photos illustrate a food family, rather than a specific preparation, and are labelled representative in the app. Disabled groups have no reviewed photo.
- Each manifest photo retains its author, license, license link, source page, acquisition URL, transformation description and content hash. Images are resized to at most 256 px, stripped of metadata and compressed as WebP. Attribution applies to each image individually; share-alike images retain the corresponding license.
- Unavailable, undecodable or nearly blank images are skipped. The app keeps a food-symbol fallback. Acquisition failures are recorded without response bodies or credentials.
- Downloads are bounded, cached and resumable. There are no runtime image requests. Never scrape arbitrary search-engine results or infer a branded product's photo from its name.

Rebuild using the same catalogue database as the Android build, Python and Pillow:

```bash
python tools/fetch_off_photo_metadata.py --food-db app/src/main/assets/offline_foods.db --export-file /path/to/en.openfoodfacts.org.products.csv.gz --output /tmp/off-photos.json
python tools/fetch_commons_photo_metadata.py --queries tools/photo_queries.json --cache /tmp/commons-cache --output /tmp/commons-photos.json
python tools/build_food_photos.py --food-db app/src/main/assets/offline_foods.db --off /tmp/off-photos.json --commons /tmp/commons-photos.json --queries tools/photo_queries.json --cache /tmp/photo-cache --assets app/src/main/assets --workers 4
python tools/validate_food_photos.py --food-db app/src/main/assets/offline_foods.db --assets app/src/main/assets
python -m unittest tools.test_photos -v
```

Download the complete official export from `https://openfoodfacts-ds.s3.eu-west-3.amazonaws.com/en.openfoodfacts.org.products.csv.gz` first; `curl --continue-at -` supports resuming the large download. Omitting `--export-file` streams the export directly, but an interrupted stream must restart. Keep exports, caches and contact sheets outside the repository. Review new Commons selections visually before committing. A fresh upstream snapshot can change image availability; the APK and CI use committed assets, not a live scrape.
