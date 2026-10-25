# LumaTV — Android IPTV player

A native Android, Netflix-inspired IPTV application with a responsive interface designed for phones, foldables and tablets. Designed for the Galaxy Z Fold 7 and other Android devices running Android 8 or higher.

**LumaTV is a player, not an IPTV subscription service.** It does not include commercial channels, provider accounts or access to licensed programming. Add only IPTV streams or catalogues you are permitted to view.

## Features

- Native dark interface with a featured banner, artwork shelves and responsive card grids
- M3U/M3U8 playlist URLs or local M3U file import
- Xtream Codes-compatible sign-in using server address, username and password
- Live TV, on-demand films and TV series categories; series/season/episode browser for Xtream
- Full-screen video player built with AndroidX Media3 ExoPlayer
- HLS, MPEG-TS, DASH and progressive video **where formats/codecs are supported by Media3 and your device**
- Resume playback for films and individual episodes
- Search across titles and categories; category chips; long-press to favourite
- XMLTV guide now/next parsing with optional EPG URL, plus Xtream EPG endpoint auto-discovery
- Picture-in-picture (PiP), aspect ratio fit/zoom, built-in playback controls
- Encrypted server configuration and login details stored via Android Keystore
- Cached catalogues for faster startup. **Video streaming and refreshing still require access to the provider.**
- A demo library containing public test/example media URLs for evaluating the interface
- Optional per-stream User-Agent and Referer support from M3U `#EXTVLCOPT`

## Build an APK with only your Samsung phone (GitHub cloud build)

Requirements: free GitHub account, Chrome/Samsung Internet (desktop site can help), and internet access. Some accounts may need Codespaces access; GitHub's usage limits may apply.

1. Download **LumaTV-Android-Source.zip** to your Samsung.
2. Sign into [github.com](https://github.com) and create a new, empty *private* repository, e.g. `LumaTV`. Don't create an initial README; this ZIP already has one.
3. In the new repository, tap **Code → Codespaces → Create codespace on main**. This opens a web-based editor, no laptop required.
4. In the Codespaces file explorer, use **Upload...** to upload your downloaded `LumaTV-Android-Source.zip` into the repository root. If the Upload option is hidden on mobile, use your browser's **Desktop site** setting.
5. Open the Codespaces terminal and enter these commands:

   ```sh
   unzip -o LumaTV-Android-Source.zip
   rm LumaTV-Android-Source.zip
   git add .
   git commit -m "Add LumaTV Android app"
   git push
   ```

6. In GitHub, open the repository's **Actions** tab and select **Build installable Android APK**. The build starts after the push (or tap **Run workflow** if required).
7. Open the successful workflow run → **Artifacts** → **LumaTV-debug-apk**. Download and extract the artifact ZIP on your Samsung. Inside is `app-debug.apk`.
8. Open `app-debug.apk` and allow installation from that source when Android prompts. You can revoke the installation permission afterwards.

> The packaged Android source is provided in this chat. The APK **has not been built or tested on a device** in this environment because the Android SDK and dependency downloads are unavailable here. The CI workflow is supplied to compile it, but build or runtime fixes may still be necessary.

## Build with Android Studio on a computer (optional)

Open the `LumaTV` directory as a Gradle project in Android Studio, use JDK 17, install Android SDK 36 and Build Tools 36, use Gradle 8.11.1, then assemble the `debug` variant. Gradle dependencies resolve from Google Maven and Maven Central. A Gradle wrapper JAR is not bundled; the CI workflow supplies Gradle automatically.

## Setting up LumaTV

- **Xtream**: Enter your provider's server base address, not the `player_api.php` path, plus your username and password. The app downloads live, film, series and episode catalogues. If live streams fail, go to Settings and switch live format from HLS/M3U8 to TS.
- **M3U link**: Enter a direct playlist URL. M3U links with group and logo metadata will display best. M3U playlists typically do not expose a standard episode catalogue, so links are treated as individual items.
- **Local M3U**: Import a local `.m3u` file. Only actual HTTP/HTTPS media links will play; relative/file/RTMP links are not supported.
- **EPG**: Optionally add an XMLTV guide URL. Xtream-compatible services are queried at their `xmltv.php` endpoint by default. Matches depend on your source's channel IDs.
- **Demo**: Shows a few public sample videos/test streams. Availability of third-party sample links can change.

## Technical information and limitations

- Package ID: `com.lumatv.app`; minimum Android SDK 26; target SDK 35; compile SDK 36.
- Kotlin 2.2.20, Android Gradle Plugin 8.10.1, Gradle 8.11.1, Media3 1.11.1.
- Xtream compatible APIs vary by provider. The app implements common `player_api.php` endpoints and their conventional video URL patterns; nonstandard providers may require adjustments.
- Live channels require a working provider account or reachable playlist. DRM-protected content, timeshift/catch-up, casting, offline video downloads, Android TV remote navigation and multiple-provider switching are **not** implemented.
- Some older or nonstandard codecs/stream containers may not play on all devices.
- Provider credentials are encrypted at rest using Android Keystore. Some IPTV providers use plain HTTP, which does **not** encrypt credentials or streams in transit. Prefer HTTPS where your provider supports it. No telemetry or account backend is added.
- No subscription billing, media rights verification or content sourcing is built in.
- Local guide information is held in memory and refreshed from the provider on startup or catalogue refresh.

## Code layout

- `Models.kt`: media models, provider configuration and shared state
- `Storage.kt`: secure provider credentials, catalogue persistence, favourites and positions
- `Net.kt`: bounded HTTP network utility
- `Sources.kt`: M3U parser, Xtream catalogue and episode API, demo library
- `EpgService.kt`: streaming XMLTV now/next parser
- `ImageLoader.kt`: asynchronous poster loading
- `Ui.kt`: dark UI design helpers
- `MainActivity.kt`: home, search, catalogue, filters, settings and source sign-in
- `SeriesActivity.kt`: show details, season navigation and episode list
- `PlayerActivity.kt`: Media3 ExoPlayer playback, resume, aspect ratio and PiP
- `.github/workflows/build-apk.yml`: GitHub Actions APK build pipeline
