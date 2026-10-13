# Dual EMA V2.4 — Android Crossover Alerts Only

This is a standalone, alert-only Android adaptation of the **V2.4 Top 100 live scanner**. It requires no Windows scanner and places no orders. It does not contain the paper-trade, audit, stress-test, testing dashboards, or saved TradingView layout features.

## Main screen

- **Two EMA tabs:** `EMA 9/15` and `EMA 8/33`
- **Timeframe selector:** All, 15m, 1H, 4H, 1W
- **Confirmed crossovers only:** bull and bear, using completed candles
- **Top 100:** dynamic Binance USD-M **USDT perpetuals** ranked by 24h quote volume (refreshed every 6 hours)
- **Tap a signal** or an Android notification to open that coin/timeframe in the live TradingView chart
- Persistent recent alert history and duplicate suppression across service restarts

The core EMA rule is ported from V2.4 `scanner.py`: seeded with the first candle close; 9/15 or 8/33 EMA crossover on two consecutive **completed** candles; 500-klines request with minimum 65 completed bars. The market-ranking eligibility matches V2.4. No EMA200 entry filter is used, matching V2.4's default baseline.

**Important differences:** This is a new Android signal history, **not an import of your Windows `scanner_state.json`**. It does not display or calculate entries, stop losses or paper trade performance. First successful scan populates the feed silently; later new crossovers trigger Android notifications. Some newly listed coins do not have enough weekly candles.

## Generate a real installable APK using GitHub Actions

The project source is ready to build; it is **not** an APK by itself.

1. Create a new GitHub repository (prefer **Private**).
2. Extract the ZIP, then upload the files and directories **inside** the project folder to the root of your repository. Keep `.github/workflows/build-apk.yml` intact.
3. Open **Actions → Build Dual EMA Android APK → Run workflow**. The workflow also runs on a push to `main` or `master`.
4. When the build is complete, open its run and download the **`DualEMA_V2_4_Alerts_APK`** artifact.
5. Unzip that artifact and install `DualEMA_V2_4_Alerts.apk` on your Android phone. Android may ask to allow installation from that source.
6. Open app → grant notification permission → tap **Start Scanner & Alerts**.

The workflow installs Java 17, Android SDK 35, Gradle 8.9 and Android Gradle Plugin 8.7.3 on GitHub's runner, then assembles a signed **debug** APK for private testing. Do not treat the debug signature as a distribution/release signing identity; installing a future differently signed APK might require uninstalling the previous version (and would erase local alert history).

## Background monitoring caveats

The app uses a persistent foreground **dataSync** service while scanning. This displays an ongoing notification and can be paused or terminated by Android (notably Android 15+ time limits and battery management); it is **not guaranteed to run indefinitely**. It checks for newly completed candle periods approximately every 90 seconds while allowed to run. Select **Unrestricted** battery use for this app if your phone supports it, and verify that its ongoing scanner notification remains visible.

Top 100 × four timeframes means up to ~400 API queries on the initial scan and about 100 every 15-minute candle thereafter, plus other close-boundary scans. This consumes battery/network, and Binance may enforce rate limits or restrict access by geography. If Binance fails, the app reports the error and does not invent signals. 

TradingView charts open with the correct market/timeframe parameters, but TradingView may override them with saved layout preferences, and custom Pine scripts are not installed automatically.

**Research alerts only.** No guarantees of alert timeliness or profitability. Do not use an Android alert alone to place a CFT trade without independently confirming timing, rules and executable prices.

## Files

- `app/src/main/java/com/dualema/alerts/CrossoverMath.java`: EMA algorithm matching V2.4.
- `app/src/main/java/com/dualema/alerts/ScannerService.java`: Top-100 live polling, deduplication, notifications.
- `app/src/main/java/com/dualema/alerts/MainActivity.java`: minimal two-EMA mobile feed.
- `app/src/main/java/com/dualema/alerts/TradingViewLinks.java`: symbol-specific TradingView links.
- `.github/workflows/build-apk.yml`: automated APK build.

## Android version

Android 8.0+ (minSdk 26), target Android 14/API 34. Android SDK availability and compilation on a GitHub runner must be verified by the first workflow run.
