# Development

## Modules

- `app`: Android phone browser, player detection, standard Cast, optional phone relay, and SimpleCast TV controls.
- `tv`: Native Android TV / Google TV receiver using AndroidX Media3. Its local HTTP server carries control messages only.
- `protocol`: Shared URL validation, request context, and supported video envelope decoding.

The phone keeps application ID `ca.sebed.castsimple` so existing installations can be updated. The TV app uses `ca.sebed.simplecast.tv`.

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

When multiple devices are connected, set `ANDROID_SERIAL` to the intended device before running a module's tests. The hardware Cast smoke test requires an explicit `castDevice` argument. The real TV pairing test requires explicit `tvEndpoint` and `tvPin` arguments. The external native playback test requires a `sourceFile` argument pointing to an on-device JSON file containing `url`, `mime`, and `headers`. These hardware diagnostics skip by default. Remove temporary source files after testing; never check their stream tokens into Git.

Keep local SDK paths, signing keys, diagnostic captures, playback tokens, and device addresses out of version control. Generated `build/` directories are ignored. Only the two intentional distribution APKs belong in `dist/`.
