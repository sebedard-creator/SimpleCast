# SimpleCast

A lightweight Android app that discovers video players on web pages and sends their streams to Chromecast. No direct video URL required, no app ads, and no added analytics.

## Features

- Detects video players, including players embedded in iframes.
- Finds MP4, WebM, HLS, and DASH streams, including manifests without file extensions.
- Supports URL sharing from browsers and playback controls for Chromecast.

## Screenshot

<img src="docs/images/simplecast.png" alt="SimpleCast Android interface" width="320">

## Getting started

Install [SimpleCast.apk](dist/SimpleCast.apk) on Android 8.0 or later. Google Play Services and an up-to-date Android System WebView are required. Connect your phone and Chromecast to the same Wi-Fi network.

1. Open a web page in the app or share its URL from your browser.
2. Start the video if needed, then select **Scanner les lecteurs**.
3. Choose a player and stream, tap **Caster**, and select your Chromecast.

A three-player demo is available under **? → Démonstration**.

## Limitations

DRM, WebRTC, transcoding, and streams requiring cookies or custom headers are not supported. Playback depends on your Chromecast's supported formats and the streaming server. Website ads remain visible.

## Build

Requires JDK 17+ and Android SDK 36.

```sh
./gradlew assembleRelease
```

On Windows, use `gradlew.bat assembleRelease`. The APK is generated in `app/build/outputs/apk/release/`. Local builds use the development signing key.

## License

[MIT](LICENSE).

**Note: The codebase and user interface are in French.**
