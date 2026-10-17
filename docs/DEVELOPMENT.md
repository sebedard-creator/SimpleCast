# Development

## Modules

- `app`: Android phone browser, automatic player/source selection, and SimpleCast TV controls. No Google Cast SDK or phone media relay.
- `tv`: Native Android TV / Google TV receiver using AndroidX Media3. Its local HTTP server carries control messages only.
- `protocol`: Shared URL validation, request context, and supported video envelope decoding.

The phone keeps application ID `ca.sebed.castsimple` so existing installations can be updated. The TV app uses `ca.sebed.simplecast.tv`.

The TV persists its paired controller token and listening port in private preferences. Stream errors and normal app restarts preserve pairing while the TV's network address stays the same. Pairing another controller rotates the token; clearing the TV app's data removes it. The receiver still runs only while the TV app is open.

Automatic sending refreshes live iframe players, prioritizes playing and visible players by rendered area, and prefers associated sources, site-provided fallback encodings, the current source, and full HLS masters. The optional source picker remains available for ambiguous pages or unsupported encodings. A local receiver fixture verifies one-tap sending without contacting a real TV.

## Browser filtering

The phone filters known ad hostnames locally using the bundled, unmodified AdAway hosts snapshot (CC BY 3.0; attribution in `app/src/main/assets/licenses/adaway.txt`). A document-start script hides explicitly named ad elements in all frames while preserving video and player containers. WebChromeClient suppresses JavaScript dialogs while filtering is enabled. The remembered menu switch disables hostname/cosmetic filtering and script-dialog suppression, then reloads the page; new-window blocking and automatic cross-site takeover protection remain active. The blocklist is bundled and is not downloaded at runtime.

## Native playback context

The phone sends the complete stream URL and observed referrer, origin, and user agent to the paired TV. An observed `X-Token` header is forwarded only for manifests on the stream's original HTTP origin, including its scheme and port. Redirects are handled explicitly to avoid sending that token to another origin; segment requests do not receive it. Browser cookies and account authorization headers stay in the browser.

The TV downloads video itself and restores supported PNG/WebP transport envelopes, including MPEG-TS appended after a WebP image's declared boundary. Ordinary video remains streamed; authenticated manifests are limited to 1 MiB. Neither this path nor the TV control server relays video through the phone.

## Build and package

Use JDK 17+ and Android SDK 36. Set `JAVA_HOME` and configure `ANDROID_HOME` or the ignored `local.properties` file as needed.

```sh
sh ./gradlew packageRelease
```

This runs JVM unit tests and release Lint checks, builds both apps, and updates `dist/SimpleCast.apk` and `dist/SimpleCast-TV.apk`. It does not install apps, create a commit, or publish a release. APKs use the local Android development signing key; retain that key to update installations signed with it.

On Windows, replace `sh ./gradlew` with `gradlew.bat`. On memory-constrained machines, add `--max-workers=1` and lower `org.gradle.jvmargs` through your local Gradle configuration.

## Tests

JVM tests run without a device:

```sh
sh ./gradlew :protocol:test :app:testDebugUnitTest
```

Detector tests require Node.js 18+:

```sh
node --test tests/detector.test.cjs
```

Android instrumentation tests exercise real WebView navigation and native video decoding. Run these only on dedicated test devices: they open activities and can interrupt playback. The TV playback fixture is a small synthetic video; it has no dependency on a streaming site.

```sh
sh ./gradlew :app:connectedDebugAndroidTest
sh ./gradlew :tv:connectedDebugAndroidTest
```

When multiple devices are connected, set `ANDROID_SERIAL` to the intended device before running a module's tests. The real TV pairing test requires explicit `tvEndpoint` and `tvPin` arguments. The external native playback test requires a `sourceFile` argument pointing to an on-device JSON file containing `url`, `mime`, and `headers`. These hardware diagnostics skip by default. Remove temporary source files after testing; never check their stream tokens into Git.

Keep local SDK paths, signing keys, diagnostic captures, playback tokens, and device addresses out of version control. Generated `build/` directories are ignored. Only the two intentional distribution APKs belong in `dist/`.
