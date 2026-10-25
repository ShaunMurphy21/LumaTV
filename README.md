# LumaTV v1.1.0 — phone, foldable & Fire TV IPTV player

Android IPTV client for **your own legally accessible M3U playlists or Xtream-compatible service**. No channels, subscriptions or commercial media are supplied.

## What's new in 1.1.0

- **Films and series:** updated M3U classification. Movie/VOD/Film groups now appear under Films; streams in Series groups or with `S01E02`, `1x02`, `Season 1 Episode 2` naming are grouped into a Series/episode browser. Xtream VOD/series endpoints are now handled independently, so a missing optional endpoint does not abort the entire import. The app automatically **refreshes the cached catalogue once after upgrading** so older, incorrectly classified items get updated.
- **Separate searches:** Home searches all titles; Live TV, Films, Series, and My List search only their own content. Switching tabs resets the search. A visible `✕` control and Android/remote Back clear search; Back also exits a category or tab.
- **Hide channels:** Settings → **Manage visible channels**. Search for individual channels and tap to hide/show, choose a category to hide/show en masse, or tap **Show all**. Hidden channels are excluded from Home, Live TV, search and My List but remain in your provider playlist. This preference persists locally across refreshes and app upgrades.
- **Episode autoplay:** automatically starts the next episode when the current episode finishes; manually skip via **Next ▶** in the player. Toggle via Settings → **Autoplay next episode**. Episode order follows season then episode number; playback resume is retained.
- **Fire TV / Android TV:** Android TV Leanback launcher entry, 320×180 banner, touchscreen optional, larger navigation for TV, D-pad-focusable cards/menus/episodes and player media-key controls. Works best on Fire OS 6+ / Android 6+ hardware. Device codecs, streaming formats, video DRM and sideloading policies still affect compatibility. Fire TV remote behavior has not been hardware-tested yet.

## Install an updated APK using your Samsung + GitHub Codespaces

1. Download `LumaTV-v1.1.0-FireTV-and-Fixes.zip` from this conversation.
2. Open your existing `https://github.com/ShaunMurphy21/LumaTV` repository, Code → Codespaces → your Codespace (create one if necessary).
3. Upload the ZIP to the **repository root** (same folder as `settings.gradle.kts`). In Samsung Internet/Chrome enable Desktop site if Upload is hidden.
4. Codespaces terminal:

   ```bash
   unzip -o LumaTV-v1.1.0-FireTV-and-Fixes.zip
   rm -f LumaTV-v1.1.0-FireTV-and-Fixes.zip
   git add -A
   git commit -m "LumaTV 1.1: VOD, series, searches, hidden channels, autoplay, Fire TV"
   git push
   ```

5. Open GitHub repository → Actions → **Build installable Android APK** → latest green run → Artifacts → `LumaTV-debug-apk` → extract `app-debug.apk`.
6. Install on Samsung, over the existing LumaTV version. Both APKs use the same `com.lumatv.app` application ID; saved account/visibility settings should remain if signed by the same debug signing key. **GitHub Actions uses a new ephemeral debug keystore on some builds**, so if Android reports *App not installed* due to conflicting signature you may have to remove the previous debug build (which deletes app data) before installing. Keep your provider login handy.
7. On initial app launch following the update the playlist **refreshes automatically**. You can also select Settings → Refresh library.

## Fire TV installation

- Copy the GitHub-built `app-debug.apk` to Fire TV through a trusted file-transfer/sideloading method (for example the `Downloader` app and a URL where **you** securely host the APK, or ADB from a computer/network). Do not expose IPTV credentials through public download links.
- On Fire TV, enable installation from your chosen source under Settings → My Fire TV → Developer Options → Install unknown apps, **if that option is available on your model/Fire OS version**. Recent Fire OS software can differ.
- Install the APK, then launch **LumaTV** from your apps library. Use the Fire TV remote D-pad / Select / Back; input your playlist/provider via the TV on-screen keyboard.
- Media formats such as provider-specific MPEG-TS/HLS, codecs and DRM vary. PiP needs Android 8+ and compatible firmware.

## Quick smoke test

For a test without a provider, connect **Explore with sample streams**. The demo now includes one live stream, sample films, and a two-episode **Demo Series**; open the Series tab and play the first episode. Switch to Films and search for `Bunny` to test scoped search. In settings, check that autoplay is on; the player should start the second demo episode after the first finishes. For channel hiding, open Settings → Manage visible channels, hide the demo live stream and return to Live TV to verify it's gone, then Show all to restore it.

## Important provider limitation

Movies/series only show when the connected service actually exposes them. Xtream providers usually supply `get_vod_streams`, `get_series` and `get_series_info`; some accounts are live-only or disable these endpoints. M3U has no universal movie/TV-series metadata standard, so inference depends on `group-title`, URL paths and episode naming. An M3U with unlabelled media cannot be reliably organised into shows automatically.

## Build stack

- Kotlin 2.2.20; Gradle 8.11.1; Android Gradle Plugin 8.10.1
- minSdk 23 (Android 6); targetSdk 35; compileSdk 36
- AndroidX Media3 (ExoPlayer, HLS, DASH), RecyclerView; Android Keystore encrypted provider credentials and catalogue
- Workflow: `.github/workflows/build-apk.yml`; `android-actions/setup-android@v4`; JDK 17

**Status:** The updated source archive has been generated and checked locally, but it has **not** been compiled by the Android SDK or tested on a physical Samsung/Fire TV device in this environment. The GitHub Actions build is still required, and device-specific bugs may remain.
