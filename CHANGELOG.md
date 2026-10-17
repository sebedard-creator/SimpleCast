# Changelog

## 0.4.2

- Add a bundled AdAway hostname blocklist, cosmetic filtering of identifiable banners in pages and frames, and suppression of JavaScript advertising dialogs.
- Preserve video containers and embedded players during cosmetic filtering, including players added dynamically.
- Add a remembered ad-filter switch under the menu for sites that need an exception; no VPN or external filtering service is used.

## 0.4.1

- Distinguish an unreachable TV from a stream playback error, with instructions to reopen SimpleCast TV and a retry action that preserves pairing.

## 0.4.0

- Simplify the phone app to one workflow: open a site, play its video, and send it to the paired SimpleCast TV with one tap.
- Select the playing video and its source automatically, favoring the current source, full HLS masters, and site-provided alternate encodings.
- Keep an optional source picker under the menu and playback errors, with persistent TV pairing and pause/stop controls.
- Remove standard Google Cast, phone relaying, their dependencies, and foreground playback permissions.
- Refresh the welcome screen, help, and usage documentation for Android TV and Google TV.

## 0.3.4

- Ignore empty video/source placeholders that resolve to the page URL, preventing HTML pages from appearing as MP4 streams.
- Read complete `videoUrl` sources from bounded JSON media arrays and definitions without treating metadata endpoints as video files.

## 0.3.3

- Exclude HLS/DASH media fragments and initialization files from selectable streams, including those served with an MP4 content type.
- Preserve full manifests and complete MP4 sources, avoiding container parsing errors caused by casting a fragment on its own.
- Detect alternate streams in bounded player source metadata and identify site-provided fallback versions, allowing a different encoding to be selected when the TV only plays audio.

## 0.3.2

- Preserve SimpleCast TV pairing credentials and its control port across normal receiver restarts (TV 0.1.2).
- Keep playback failures separate from pairing, so another stream can be selected without entering a new PIN.
- Return to the TV welcome screen with Back after a playback error, without closing the receiver.
- Handle invalid media setup without crashing the TV receiver, and add error recovery and pairing persistence tests.

## 0.3.1

- Forward observed `X-Token` manifest headers to SimpleCast TV 0.1.1, fixing sources that require a temporary stream token in addition to the URL.
- Keep manifest tokens on the original HTTP origin, including redirects, and exclude them from video segment requests.
- Recognize MPEG-TS segments appended after the declared WebP image boundary.

## 0.3.0

- Add SimpleCast TV 0.1.0 for direct Android TV and Google TV playback, with local discovery, code pairing, and phone controls.
- Forward the source site's referrer, origin, and user agent to the TV without relaying video through the phone.
- Decode supported PNG/WebP video envelopes on the TV or through the optional phone relay.
- Keep the phone relay disabled by default and require explicit selection to start it.
- Block popups and selected advertising scripts while preserving embedded players.
- Refresh player lists after iframe changes and prioritize associated streams and recent URLs.
- Preserve newly detected URLs when the stream catalog reaches capacity.
- Report available Cast errors and native TV playback errors, and refresh the bundled demo sources.
- Add a shared playback module, regression tests, and a `packageRelease` task for both APKs.
