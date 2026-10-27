# Binance Phone Scanner — Paper Scalping Simulator

This project keeps the existing scanner service and detection conditions unchanged. The former history screen has been replaced with a paper-scalping simulation screen.

## Build
- Open this repository in Android Studio and build `app` / `assembleDebug`, or push it to GitHub and run **Actions → Build Android APK → Run workflow**.
- APK artifact: `binance-phone-scanner-debug-apk`.
- Package: `com.example.binancephonescanner`, min SDK 26.

## Paper trading behavior
- The simulator reads detection records for the selected watch and processes them in detection-time order.
- Entry price is the saved detection price.
- Stop-loss can be either (a) the low of the configured n-th previous completed 1-hour candle, or (b) a fixed percentage below entry.
- Take-profit is a configurable percentage above entry.
- Historical USDⓈ-M futures 1-minute candles are checked after detection. If both stop and target are crossed within the same minute candle, stop-loss is assumed first (conservative tie-break).
- The summary compounds the balance in detection order, using the full balance per completed trade. Fee estimate is 0.05% taker fee on entry plus 0.05% on exit (0.10% round trip), with no slippage. Fee tier can vary by account, discounts, and product; this is a simulation assumption, not an account-specific quote.
- The record-wide delete only removes simulator output, not scanner detections.
- Scan history retention was extended to 100 years so this record workflow is not automatically purged after five days. Existing records already purged by earlier versions cannot be recovered.

## Notes
- Public Binance USDⓈ-M Futures endpoints are used; no API key is required.
- Historical data requests require internet access and can take time for many detections.
- Open trades are labeled `진행 중` and are not included in closed-trade win/loss counts or compound balance until an exit level is reached.


## Build fix: ViewPager dependency

`MainActivity.kt` imports `androidx.viewpager.widget.ViewPager` and `PagerAdapter`. The app module must include `implementation("androidx.viewpager:viewpager:1.0.0")` in `app/build.gradle.kts`. This dependency is included in this archive. When updating GitHub, upload/replace the entire project, including `app/build.gradle.kts`, not only the Kotlin source files.
