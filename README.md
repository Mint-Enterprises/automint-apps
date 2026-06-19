# Automint Apps

Client applications for [Automint](https://automint.org), a crypto escrow
platform, across desktop and mobile.

This repository is published for transparency and reference. The source is
viewable but proprietary — see [LICENSE](LICENSE).

## Platforms

| Directory | Platform | Stack |
| --- | --- | --- |
| [`windows/`](windows/) | Windows desktop | Electron |

Additional platforms (macOS, Linux, Android, iOS) will be added over time.

## Windows desktop

A native desktop wrapper around the Automint web app, built with Electron.

**Notable features**

- Never miss a ping — native push notifications with action buttons and a
  taskbar badge count
- Optimised for all types of modern Windows — Mica on Windows 11, graceful
  fallback on Windows 10, GPU-accelerated rendering
- Mini picture-in-picture view that stays on top
- System tray, frameless custom title bar, `automint://` deep links, and
  background auto-updates

```sh
cd windows
npm install
npm start        # or: npm run dev  (opens dev tools)
```

Requires Node.js 18+. Build a distributable with `npm run build:win`.

## License

Copyright (c) 2026 AutoMint. All rights reserved.

Proprietary, source-available software. You may read the code, but copying,
modifying, using, deploying, or redistributing it requires written permission.
The full terms are in [LICENSE](LICENSE).

Licensing inquiries: contact@hilfing.dev
