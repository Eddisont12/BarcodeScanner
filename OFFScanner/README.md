# OFFScanner (Android)

Simple Android barcode scanner using CameraX + ML Kit, fetching product data from Open Food Facts and showing ingredients. Caches results locally.

## Requirements
- Android Studio Koala or later
- Android SDK 34
- Device/emulator with camera (physical device recommended)

## Build & Run
1. Open the `OFFScanner` folder in Android Studio
2. Sync Gradle
3. Run on a physical device (grant camera permission)

## Features
- CameraX + ML Kit barcode scanning (EAN-13/8, UPC-A/E)
- Fetches product via OFF `/api/v0/product/{barcode}.json`
- Displays product name and ingredients (`ingredients_text_en` fallback to `ingredients_text`)
- Simple SharedPreferences cache keyed by barcode

## API Usage Etiquette
- Always send a custom `User-Agent` identifying your app and contact (required by OFF)
- Cache results to reduce load and respect rate limits
- For heavy/offline use, use the official dumps (JSONL/Parquet) and query locally

## Next Steps
- Normalize ingredients using `ingredients_tags`/`ingredients_hierarchy`
- Cross-reference against user-provided carcinogen list
- Offline mode via OFF dumps + DuckDB

## Credits
- CameraX, ML Kit
- Open Food Facts