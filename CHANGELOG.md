# Changelog

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
