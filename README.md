# SimpleCast

Cast compatible video streams to your TV, even when the website's player has no Cast button or disables casting.

A lightweight Android browser that sends the video you are watching directly to SimpleCast TV on Android TV or Google TV. Open a site, start a video, and tap one button. No direct video URL required, no app ads, and no added analytics.

- Detects players in pages and iframes, including MP4, WebM, HLS, and DASH streams.
- Automatically selects the playing video and its stream, with alternate sources available from the menu.
- Blocks known ad hosts and popups, and hides identifiable banners in pages and embedded frames.
- Remembers your paired TV and provides playback controls without relaying video through the phone.

<img src="docs/images/simplecast.png" alt="SimpleCast Android interface" width="320">

## Install

| APK | Version | Device |
| --- | --- | --- |
| [SimpleCast.apk](dist/SimpleCast.apk) | 0.4.2 | Android phone, Android 8.0+ |
| [SimpleCast-TV.apk](dist/SimpleCast-TV.apk) | 0.1.2 | Android TV or Google TV, Android 8.0+ |

Install SimpleCast TV on the TV and keep both devices on the same local network. The phone requires an up-to-date Android System WebView. Chromecast with Google TV is supported through the TV app; older Chromecast devices without app installation are not supported.

## Use

**First use:** Open SimpleCast TV on the TV. On the phone, tap **Associer une télé**, select your TV, and enter its displayed code. Pairing is remembered, including after stream errors and normal TV app restarts.

1. Enter the streaming site's URL, or share it from your browser.
2. Navigate to your video and press play.
3. Tap **Envoyer à la télé**.

The TV downloads the video directly. Once playback starts, you can turn off the phone's screen. Keep SimpleCast TV open on the TV. If the phone shows **Télé indisponible**, reopen the TV app and retry; this does not mean the video source is incompatible. Pause and stop controls appear on the phone during TV playback.

Use **⋮ → Autre source vidéo** if a source fails or only plays audio. Tap the TV's name at the top to switch receivers. Help and a demo are available under **⋮ → Aide**.

The local ad filter is on by default. Disable **⋮ → Filtre antipub** if a site stops working; the page reloads and your choice is remembered. No VPN or filtering service is required.

## Limitations

DRM, WebRTC, and transcoding are not supported. Playback depends on the server and device codecs. Browser cookies and account authorization headers are not transferred. Live links and temporary stream tokens can expire; restart the video on the site and send it again if needed. The ad filter cannot remove every ad, including ads embedded in the video itself.

## Build

Requires JDK 17+ and Android SDK 36. Generate both release APKs in `dist/`, with unit tests and Android Lint checks:

```sh
sh ./gradlew packageRelease
```

On Windows, use `gradlew.bat packageRelease`. Builds use the local development signing key. See [development notes](docs/DEVELOPMENT.md) for project structure and additional tests, and [the changelog](CHANGELOG.md) for recent changes.

## License

[MIT](LICENSE). Third-party components retain their own licenses, including [NanoHTTPD](tv/src/main/assets/licenses/nanohttpd.txt) and [AndroidX Media3](tv/src/main/assets/licenses/media3.txt).

The bundled [AdAway blocklist](https://github.com/AdAway/adaway.github.io/) is by Kicelo, Dominik Schuermann, and contributors, under [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). See its [attribution notice](app/src/main/assets/licenses/adaway.txt).

**Note: The codebase and user interface are in French.**
