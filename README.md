# Automint Apps

Source for the official [Automint](https://automint.online) Windows and Android
clients. Automint is a crypto escrow and middleman trading platform.

This repository is published for transparency and reference. The source is
viewable but proprietary; see [LICENSE](LICENSE).

## Current releases

| Directory | Platform | Version | Channel | Distribution |
| --- | --- | --- | --- | --- |
| [`windows/`](windows/) | Windows desktop (Electron) | `1.0.3` | Beta | Direct installer and in-app updates |
| [`android/`](android/) | Android (Kotlin) | `1.0.4` (`versionCode` 6) | Beta | Direct signed APK |

The Android app is APK-only. It is not distributed through Google Play and does
not depend on Play Integrity for installation, updates, or integrity checks.

## Windows desktop

```sh
cd windows
npm install
npm test
npm run build:win
```

Use Node.js 18 or newer. `npm start` launches the normal client and
`npm run dev` launches it with developer tools enabled.

## Android

Open `android/` in Android Studio, or build from a shell with JDK 17:

```sh
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

A signed release build additionally requires a private release keystore,
`keystore.properties`, and `app/google-services.json`. Those credentials are
intentionally not included here. The public signing certificate is available at
[`android/signing/automint-release-cert.pem`](android/signing/automint-release-cert.pem).

## License

Copyright (c) 2026 AutoMint. All rights reserved. Copying, modifying, using,
deploying, or redistributing this source requires written permission. See
[LICENSE](LICENSE) for the full terms.

Licensing inquiries: contact@hilfing.dev
