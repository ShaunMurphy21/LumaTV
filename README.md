# LumaTV 1.2.0 — Films & Series catalogue repair

An Android and Fire TV IPTV player for **IPTV content you are authorised to access**. The app does not provide any channels, subscriptions, media or IPTV credentials.

## What changed in 1.2.0

- **Fix silent VOD/Series failures.** In previous versions, `get_vod_streams` and `get_series` errors were caught and treated as empty lists. Now the app records a readable, credential-safe result for each catalogue request.
- **More compatible Xtream responses.** Supports direct JSON arrays and common object-wrapped arrays (`data`, `results`, `items`, `streams`, `movies`, `series`, `categories`).
- **Fallback by category.** If the bulk `get_vod_streams` / `get_series` endpoint returns an empty result, an unsupported format or an oversized catalogue, try `category_id`-filtered calls where categories are available. Retries are bounded to protect your device and won't repeat explicit HTTP 401, 403 or 429 failures.
- **M3U playlist enrichment.** If a live-only or partly populated M3U uses a standard `get.php?username=...&password=...` link, try the same provider's Xtream VOD/Series catalogue. Already-listed M3U channels are preserved. M3U files or URLs without credentials do not automatically provide other media.
- **Episode browser for discovered shows.** Xtream series discovered through M3U use `get_series_info` for their episodes.
- **Catalogue diagnostics.** Settings → **Catalogue diagnostics** shows counts and API response statuses (without usernames, passwords or URLs). There is also a diagnostics button on the empty Films and Series screens.
- **Automatic catalogue refresh.** On first launch after upgrading from v1.1.x, the app refreshes the saved library; provider settings, channel visibility choices and favourites are retained when the APK signing key matches.
- Fire TV launcher, remote support, hidden channel filtering, separate search tabs and episode autoplay remain available.

**Important:** Film and series imports still depend on the provider exposing them to the account and responding to Xtream/M3U requests. This update fixes several client-side failure paths but cannot guarantee any particular provider's content.

## Build from your Samsung using GitHub Codespaces

1. Download `LumaTV-v1.2.0-Catalogue-Fix.zip` from ChatGPT and upload it to the root folder of your Codespace for `https://github.com/ShaunMurphy21/LumaTV` (the same folder containing `settings.gradle.kts`).
2. In the Codespaces terminal run:

   ```bash
   unzip -o LumaTV-v1.2.0-Catalogue-Fix.zip
   rm -f LumaTV-v1.2.0-Catalogue-Fix.zip
   git add -A
   git commit -m "Fix Xtream films and series catalogue loading v1.2.0"
   git push
   ```

3. Visit `https://github.com/ShaunMurphy21/LumaTV/actions` to verify the build passed.
4. Visit `https://github.com/ShaunMurphy21/LumaTV/releases`. If your existing Releases workflow has permission to publish, download the new `LumaTV-build-N.apk` from the latest successful pre-release, or use the Actions artifact.
5. Install the APK on your Samsung or sideload it to a compatible Fire TV device.

## Test after updating

1. Open the app and allow the initial library refresh to complete, or choose **Settings → Refresh library**.
2. Open **Films** and **Series**. If either is empty, choose **View catalogue diagnostics** (or Settings → Catalogue diagnostics).
3. The report distinguishes 0 returned, HTTP 401/403, requests timed out, unsupported JSON, and category-by-category fallback. Share the **report text only** if more help is needed. Do not share the IPTV URL, username, password, or screenshots containing them.
4. If connected through an M3U file or an opaque M3U link, and the provider gives you a separate Xtream server/username/password, try connecting through **Connect Xtream provider** for full VOD and series support.
5. Once the Series tab has entries, select a show and verify that its seasons/episodes load.

## Debug build warning

The existing GitHub Actions workflow makes a **debug-signed APK**, and different runner keystores can prevent upgrades over earlier builds. If Android reports a signing conflict, uninstalling the old version before installing the new one removes local settings and provider credentials. Keep your details safely stored elsewhere. For seamless future updates, configure persistent APK signing through GitHub Actions secrets.

## Validation status

The updated `Sources.kt` compiled using a local Kotlin compiler with Android/JSON test stubs. A local M3U fixture passed checks for Live, Films, Series and episode grouping. A **full Android Gradle build and real-provider test have not been performed** in this environment; GitHub Actions must compile the APK and the source must be tested against your provider.
