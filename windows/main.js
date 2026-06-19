const Sentry = require("@sentry/electron/main");

if (process.env.SENTRY_DSN) {
  Sentry.init({ dsn: process.env.SENTRY_DSN });
}
const {
  app,
  BaseWindow,
  BrowserWindow,
  WebContentsView,
  ipcMain,
  shell,
  Tray,
  Menu,
  nativeImage,
  screen,
  session,
  Notification,
  dialog,
  globalShortcut,
  clipboard,
} = require("electron");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const Store = require("electron-store").default;
const { autoUpdater } = require("electron-updater");
const desktopFingerprint = require("./shared/fingerprint");
const { createTelemetry } = require("./shared/telemetry");

app.commandLine.appendSwitch("enable-gpu-rasterization");
app.commandLine.appendSwitch("enable-zero-copy");
app.commandLine.appendSwitch("ignore-gpu-blocklist");

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
  process.exit(0);
}

const IS_DEV = process.argv.includes("--dev");
const PROTOCOL = "automint";
const TARGET_URL = IS_DEV
  ? "http://localhost:3000"
  : "https://app.example.com";
const UPDATE_URL = "https://updates.example.com";
const TITLEBAR_HEIGHT = 36;
const ALLOWED_SETTING_KEYS = [
  "notifications",
  "minimizeToTray",
  "startOnBoot",
  "spellCheck",
  "enableTelemetry",
];

const ALLOWED_DOMAINS = [
  "example.com",
  new URL(TARGET_URL).hostname,
  "accounts.google.com",
  "oauth2.googleapis.com",
  "oauth.telegram.org",
  "challenges.cloudflare.com",
];

function asarUnpackedPath(...segments) {
  const inAsar = `${path.sep}app.asar${path.sep}`;
  const unpacked = `${path.sep}app.asar.unpacked${path.sep}`;
  const p = path.join(__dirname, ...segments);
  return p.includes(inAsar) ? p.replace(inAsar, unpacked) : p;
}

function devLog(tag, ...args) {
  if (IS_DEV) console.log(`[${tag}]`, ...args);
}

function devError(tag, ...args) {
  if (IS_DEV) console.error(`[${tag}]`, ...args);
}

const store = new Store({
  name: "automint-settings",
  defaults: {
    bounds: { width: 1280, height: 800 },
    isMaximized: false,
    notifications: true,
    minimizeToTray: true,
    startOnBoot: false,
    spellCheck: true,
    enableTelemetry: true,
    zoomLevel: 0,
  },
});

const telemetryStore = new Store({ name: "automint-telemetry" });

const telemetry = createTelemetry({
  settingsStore: store,
  queueStore: telemetryStore,
  fingerprint: desktopFingerprint,
  netFetch: (url, opts) => require("electron").net.fetch(url, opts),
  isOnline: () => require("electron").net.isOnline(),
  now: () => Date.now(),
  randomId: () => crypto.randomUUID(),
  endpoint: TARGET_URL + "/api/telemetry",
  log: devError,
});

let win = null;
let titlebarView = null;
let contentView = null;
let splashView = null;
let tray = null;
let pipWin = null;
let isQuitting = false;

function buildDesktopBridgeScript() {

  const fp = desktopFingerprint.collect();
  const fpLiteral = JSON.stringify(fp).replace(/</g, "\\u003c");
  return `(function() {
  if (window.__automintBridgeReady) return;
  window.__automintBridgeReady = true;

  // ── Desktop fingerprint (read-only, frozen) ──────
  // Web app's lib/fingerprint/collect.ts checks for window.automintDesktop
  // and folds .fingerprint into the custom block when present.
  try {
    Object.defineProperty(window, 'automintDesktop', {
      value: Object.freeze({
        isDesktop: true,
        fingerprint: Object.freeze(${fpLiteral}),
      }),
      writable: false,
      configurable: false,
      enumerable: true,
    });
  } catch {}

  // ── Sound interception ──────────────────────────
  // The website plays sounds via new Audio('/sounds/xyz.wav').play()
  // Map sound file paths to event types for the desktop app.
  const SOUND_MAP = {
    'deal-positive': 'positive',
    'deal-alert': 'alert',
    'notification': 'notification',
    'mintmp-mention': 'notification',
    'mintmp-broadcast': 'notification',
  };

  const OriginalAudio = window.Audio;
  const origPlay = HTMLAudioElement.prototype.play;

  HTMLAudioElement.prototype.play = function() {
    const src = this.src || '';
    for (const [key, type] of Object.entries(SOUND_MAP)) {
      if (src.includes(key)) {
        try { window.automint._soundPlayed(type); } catch {}
        break;
      }
    }
    return origPlay.call(this);
  };

  // ── Notification interception ───────────────────
  // The website creates browser Notification objects. Intercept them
  // to relay data to the desktop app for actionable native toasts.
  const OriginalNotification = window.Notification;

  class DesktopNotification extends OriginalNotification {
    constructor(title, options = {}) {
      super(title, options);
      try {
        window.automint._notificationShown({
          title: title,
          body: options.body || '',
          tag: options.tag || '',
          url: options.data?.url || '',
          silent: options.silent || false,
        });
      } catch {}
    }
  }

  // Preserve static properties
  Object.defineProperty(DesktopNotification, 'permission', {
    get: () => OriginalNotification.permission,
  });
  DesktopNotification.requestPermission = OriginalNotification.requestPermission.bind(OriginalNotification);

  window.Notification = DesktopNotification;
})();`;
}

let _desktopFingerprintInstalled = false;

function installDesktopFingerprint(ses) {
  if (_desktopFingerprintInstalled) return;
  _desktopFingerprintInstalled = true;

  const fp = desktopFingerprint.collect();
  const targetHost = new URL(TARGET_URL).hostname;
  const cookieUrl = TARGET_URL;
  const twoYearsSec = 60 * 60 * 24 * 365 * 2;

  ses.cookies
    .set({
      url: cookieUrl,
      name: "_amfp_did",
      value: fp.deviceId,
      path: "/",
      sameSite: "lax",
      secure: cookieUrl.startsWith("https:"),
      expirationDate: Math.floor(Date.now() / 1000) + twoYearsSec,
    })
    .catch((err) => devError("fingerprint", "cookie plant failed:", err));

  ses.webRequest.onBeforeSendHeaders((details, callback) => {
    try {
      const host = new URL(details.url).hostname;
      const sameOrigin =
        host === targetHost ||
        host.endsWith("." + targetHost) ||
        host === "example.com" ||
        host.endsWith(".example.com");
      if (!sameOrigin) {
        callback({ requestHeaders: details.requestHeaders });
        return;
      }
      const stamp = desktopFingerprint.headers();
      callback({
        requestHeaders: { ...details.requestHeaders, ...stamp },
      });
    } catch {
      callback({ requestHeaders: details.requestHeaders });
    }
  });

  devLog("fingerprint", "installed; deviceId=", fp.deviceId);
}

function isAllowedUrl(url) {
  try {
    const { hostname } = new URL(url);
    return ALLOWED_DOMAINS.some(
      (domain) => hostname === domain || hostname.endsWith("." + domain),
    );
  } catch {
    return false;
  }
}

function safeOpenExternal(url) {
  try {
    const { protocol } = new URL(url);
    if (protocol === "https:" || protocol === "http:") {
      shell.openExternal(url);
    }
  } catch {}
}

function isNextAuthSignIn(url) {
  try {
    const parsed = new URL(url);
    const targetHost = new URL(TARGET_URL).hostname;
    return (
      parsed.hostname === targetHost &&
      parsed.pathname.startsWith("/api/auth/signin/")
    );
  } catch {
    return false;
  }
}

function isDesktopLoginRedirect(url) {
  try {
    const parsed = new URL(url);
    const targetHost = new URL(TARGET_URL).hostname;
    return (
      parsed.hostname === targetHost &&
      parsed.pathname === "/login" &&
      parsed.searchParams.has("callbackUrl")
    );
  } catch {
    return false;
  }
}

const OAUTH_DOMAINS = [
  "accounts.google.com",
  "oauth2.googleapis.com",
  "oauth.telegram.org",
];

function isOAuthUrl(url) {
  try {
    const { hostname } = new URL(url);
    return OAUTH_DOMAINS.some(
      (domain) => hostname === domain || hostname.endsWith("." + domain),
    );
  } catch {
    return false;
  }
}

function getOAuthProvider(url) {
  try {
    const { hostname } = new URL(url);
    if (hostname.includes("google")) return "Google";
    if (hostname.includes("telegram")) return "Telegram";
    return "your account";
  } catch {
    return "your account";
  }
}

function getOAuthProviderId(url) {
  try {
    const { hostname } = new URL(url);
    if (hostname.includes("google")) return "google";
    if (hostname.includes("telegram")) return "telegram";
    return null;
  } catch {
    return null;
  }
}

function openOAuthInBrowser(url) {

  const loginUrl = new URL(TARGET_URL + "/login");
  loginUrl.searchParams.set("callbackUrl", "/auth/desktop-callback");
  devLog("oauth", "Original OAuth URL:", url);
  devLog("oauth", "Opening login page:", loginUrl.toString());
  safeOpenExternal(loginUrl.toString());
}

async function showOAuthPrompt(url, parentWin = win) {
  const provider = getOAuthProvider(url);
  const { response } = await dialog.showMessageBox(parentWin, {
    type: "question",
    icon: nativeImage.createFromPath(path.join(__dirname, "icons", "icon.png")),
    title: "Sign in with " + provider,
    message: "Where would you like to sign in?",
    detail:
      "Your browser probably has " +
      provider +
      " already signed in, " +
      "so it might be quicker there.\n\n" +
      "If you sign in using your browser, you'll be redirected back to the app once you're done.",
    buttons: ["Use my browser", "Stay in the app"],
    defaultId: 0,
    cancelId: 1,
  });
  return response === 0 ? "browser" : "app";
}

function attachCleanUserAgent(view) {
  const wc = view.webContents;
  const ua =
    wc
      .getUserAgent()
      .replace(/\s*Electron\/[\w.-]+/, "")
      .replace(/\s*AutomintDesktop\/[\w.-]+/, "") + " AutomintDesktop/1.0.0";
  wc.setUserAgent(ua);
}

function attachDesktopBridge(view) {
  const wc = view.webContents;
  wc.once("did-finish-load", () => {
    wc.setZoomLevel(store.get("zoomLevel", 0));
    wc
      .executeJavaScript(buildDesktopBridgeScript())
      .then(() => devLog("bridge", "Injected on initial load"))
      .catch((err) => devError("bridge", "Failed to inject on initial load:", err));
  });
  wc.on("did-navigate", (_event, url) => {
    devLog("navigate", "did-navigate →", url);
    wc
      .executeJavaScript(buildDesktopBridgeScript())
      .then(() => devLog("bridge", "Re-injected after navigation"))
      .catch((err) => devError("bridge", "Failed to re-inject:", err));
  });
}

function attachNavigationGuards(view, parentWin) {
  const wc = view.webContents;
  const loadInApp = (url) => {
    if (!wc.isDestroyed()) wc.loadURL(url);
  };

  wc.on("will-navigate", (event, url) => {
    devLog("routing", "will-navigate →", url);

    if (isDesktopLoginRedirect(url)) {
      devLog("routing", "Matched: desktop login redirect — opening in browser");
      event.preventDefault();
      safeOpenExternal(url);
    } else if (isNextAuthSignIn(url)) {
      devLog("routing", "Matched: NextAuth sign-in");
      event.preventDefault();
      showOAuthPrompt(url, parentWin).then((choice) => {
        devLog("routing", "OAuth prompt choice:", choice);
        if (choice === "browser") {
          const signInUrl = new URL(url);
          signInUrl.searchParams.set("callbackUrl", "/auth/desktop-callback");
          devLog("routing", "Opening in browser:", signInUrl.toString());
          safeOpenExternal(signInUrl.toString());
        } else {
          loadInApp(url);
        }
      });
    } else if (isOAuthUrl(url)) {
      devLog("routing", "Matched: OAuth URL");
      event.preventDefault();
      showOAuthPrompt(url, parentWin).then((choice) => {
        devLog("routing", "OAuth prompt choice:", choice);
        if (choice === "browser") {
          openOAuthInBrowser(url);
        } else {
          loadInApp(url);
        }
      });
    } else if (!isAllowedUrl(url)) {
      devLog("routing", "Blocked — opening external:", url);
      event.preventDefault();
      safeOpenExternal(url);
    } else {
      devLog("routing", "Allowed — navigating in-app");
    }
  });

  wc.setWindowOpenHandler(({ url }) => {
    devLog("routing", "window-open →", url);
    if (isDesktopLoginRedirect(url)) {
      devLog("routing", "window-open: Matched desktop login redirect — opening in browser");
      safeOpenExternal(url);
    } else if (isNextAuthSignIn(url)) {
      devLog("routing", "window-open: Matched NextAuth sign-in");
      showOAuthPrompt(url, parentWin).then((choice) => {
        devLog("routing", "window-open: OAuth prompt choice:", choice);
        if (choice === "browser") {
          const signInUrl = new URL(url);
          signInUrl.searchParams.set("callbackUrl", "/auth/desktop-callback");
          devLog("routing", "window-open: Opening in browser:", signInUrl.toString());
          safeOpenExternal(signInUrl.toString());
        } else {
          loadInApp(url);
        }
      });
    } else if (isOAuthUrl(url)) {
      devLog("routing", "window-open: Matched OAuth URL");
      showOAuthPrompt(url, parentWin).then((choice) => {
        devLog("routing", "window-open: OAuth prompt choice:", choice);
        if (choice === "browser") {
          openOAuthInBrowser(url);
        } else {
          loadInApp(url);
        }
      });
    } else if (isAllowedUrl(url)) {
      devLog("routing", "window-open: Allowed — loading in-app");
      loadInApp(url);
    } else {
      devLog("routing", "window-open: Blocked — opening external");
      safeOpenExternal(url);
    }
    return { action: "deny" };
  });
}

function attachContextMenu(view, parentWin) {
  const wc = view.webContents;
  wc.on("context-menu", (_event, params) => {
    const menuItems = [];

    if (params.isEditable && params.misspelledWord) {
      const suggestions = params.dictionarySuggestions || [];
      if (suggestions.length > 0) {
        suggestions.slice(0, 5).forEach((suggestion) => {
          menuItems.push({
            label: suggestion,
            click: () => wc.replaceMisspelling(suggestion),
          });
        });
      } else {
        menuItems.push({ label: "No suggestions", enabled: false });
      }
      menuItems.push({ type: "separator" });
    }

    if (params.linkURL) {
      menuItems.push({
        label: "Open link in browser",
        click: () => safeOpenExternal(params.linkURL),
      });
      menuItems.push({
        label: "Copy link address",
        click: () => clipboard.writeText(params.linkURL),
      });
      menuItems.push({ type: "separator" });
    }

    if (params.mediaType === "image" && params.srcURL) {
      menuItems.push({
        label: "Save image as...",
        click: () => wc.downloadURL(params.srcURL),
      });
      menuItems.push({
        label: "Copy image",
        click: () => wc.copyImageAt(params.x, params.y),
      });
      menuItems.push({
        label: "Open image in browser",
        click: () => safeOpenExternal(params.srcURL),
      });
      menuItems.push({ type: "separator" });
    }

    if (params.isEditable) {
      menuItems.push({
        label: "Cut",
        click: () => wc.cut(),
        enabled: !!params.selectionText,
      });
      menuItems.push({
        label: "Copy",
        click: () => wc.copy(),
        enabled: !!params.selectionText,
      });
      menuItems.push({
        label: "Paste",
        click: () => wc.paste(),
      });
    } else if (params.selectionText) {
      menuItems.push({
        label: "Copy",
        click: () => wc.copy(),
      });
    }

    menuItems.push({
      label: "Select All",
      click: () => wc.selectAll(),
    });
    menuItems.push({ type: "separator" });
    menuItems.push({
      label: "Reload",
      click: () => wc.reload(),
    });

    Menu.buildFromTemplate(menuItems).popup({ window: parentWin });
  });
}

function getSavedBounds() {
  const saved = store.get("bounds", { width: 1280, height: 800 });
  if (saved.x !== undefined && saved.y !== undefined) {
    const onScreen = screen
      .getAllDisplays()
      .some(
        ({ workArea: d }) =>
          saved.x >= d.x &&
          saved.y >= d.y &&
          saved.x < d.x + d.width &&
          saved.y < d.y + d.height,
      );
    if (!onScreen) return { width: saved.width, height: saved.height };
  }
  return saved;
}

let boundsTimeout = null;
function saveBounds() {
  if (!win || win.isDestroyed() || win.isMaximized()) return;
  clearTimeout(boundsTimeout);
  boundsTimeout = setTimeout(() => {
    store.set("bounds", win.getBounds());
  }, 300);
}

let settingsOpen = false;
let splashActive = true;

function layoutViews() {
  if (!win || win.isDestroyed()) return;
  const { width, height } = win.getContentBounds();

  if (splashActive) {

    splashView.setBounds({ x: 0, y: 0, width, height });

    titlebarView.setBounds({ x: 0, y: 0, width, height: TITLEBAR_HEIGHT });
    contentView.setBounds({
      x: 0,
      y: TITLEBAR_HEIGHT,
      width,
      height: height - TITLEBAR_HEIGHT,
    });
  } else {
    const titlebarHeight = settingsOpen ? height : TITLEBAR_HEIGHT;

    contentView.setBounds({
      x: 0,
      y: TITLEBAR_HEIGHT,
      width,
      height: height - TITLEBAR_HEIGHT,
    });
    titlebarView.setBounds({ x: 0, y: 0, width, height: titlebarHeight });
  }
}

function wireContentLoadListeners() {
  const onSuccess = () => {
    contentView.webContents.removeListener("did-fail-load", onFail);
    if (splashView && !splashView.webContents.isDestroyed()) {
      splashView.webContents.send("website-ready");
    }
  };
  const onFail = (_event, errorCode, errorDesc) => {
    devError("content-load", "Failed:", errorCode, errorDesc);
    telemetry.track("content.load_failed", { code: errorCode });
    contentView.webContents.removeListener("did-finish-load", onSuccess);
    if (splashView && !splashView.webContents.isDestroyed()) {
      splashView.webContents.send("website-failed", errorCode, errorDesc);
    }
  };
  contentView.webContents.once("did-finish-load", onSuccess);
  contentView.webContents.once("did-fail-load", onFail);
}

function broadcastOnlineStatus(online) {
  if (titlebarView && !titlebarView.webContents.isDestroyed()) {
    titlebarView.webContents.send("online-status-changed", online);
  }
}

let connectionQualityInterval = null;

function startConnectionQualityMonitor() {
  async function check() {
    const { net } = require("electron");
    if (!net.isOnline()) {
      broadcastConnectionQuality("offline");
      return;
    }
    try {
      const start = Date.now();
      const res = await net.fetch(TARGET_URL + "/favicon.ico", {
        method: "HEAD",
        signal: AbortSignal.timeout(5000),
      });
      const latency = Date.now() - start;
      if (!res.ok) {
        broadcastConnectionQuality("poor");
      } else if (latency < 300) {
        broadcastConnectionQuality("good");
      } else if (latency < 800) {
        broadcastConnectionQuality("medium");
      } else {
        broadcastConnectionQuality("poor");
      }
    } catch {
      broadcastConnectionQuality("offline");
    }
  }

  check();
  connectionQualityInterval = setInterval(check, 15000);
}

function broadcastConnectionQuality(quality) {
  if (titlebarView && !titlebarView.webContents.isDestroyed()) {
    titlebarView.webContents.send("connection-quality", quality);
  }
}

async function exchangeDesktopToken(token) {
  devLog("desktop-exchange", "Starting token exchange...");
  try {
    const ses = contentView.webContents.session;
    const url = TARGET_URL + "/api/auth/desktop-exchange";
    devLog("desktop-exchange", "POST", url);
    const response = await ses.fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token }),
    });

    devLog("desktop-exchange", "Status:", response.status, response.statusText);
    if (response.ok) {
      devLog("desktop-exchange", "Success — navigating to dashboard");
      contentView.webContents.loadURL(TARGET_URL + "/dashboard");
      if (win && !win.isDestroyed()) {
        win.show();
        win.focus();
      }
    } else {
      const body = await response.text();
      devError("desktop-exchange", "Failed:", response.status, body);
      contentView.webContents.loadURL(TARGET_URL + "/login?error=DesktopAuthFailed");
    }
  } catch (err) {
    devError("desktop-exchange", "Error:", err);
    contentView.webContents.loadURL(TARGET_URL + "/login?error=DesktopAuthFailed");
  }
}

async function exchangeReauthToken(token) {
  devLog("reauth-exchange", "Starting reauth token exchange...");
  try {
    const ses = contentView.webContents.session;
    const url = TARGET_URL + "/api/reauth/desktop-exchange";
    devLog("reauth-exchange", "POST", url);
    const response = await ses.fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token }),
    });

    devLog("reauth-exchange", "Status:", response.status, response.statusText);
    if (response.ok) {
      devLog("reauth-exchange", "Success — reloading current page");
      if (win && !win.isDestroyed()) {
        win.show();
        win.focus();
      }
      contentView.webContents.reload();
    } else {
      const body = await response.text();
      devError("reauth-exchange", "Failed:", response.status, body);
    }
  } catch (err) {
    devError("reauth-exchange", "Error:", err);
  }
}

function handleDeepLink(url) {
  devLog("deep-link", "Received:", url);
  if (!url || !url.startsWith(PROTOCOL + "://")) return;

  let dlKind = "nav";
  if (/^automint:\/\/auth\/callback/.test(url)) dlKind = "auth";
  else if (/^automint:\/\/reauth\/callback/.test(url)) dlKind = "reauth";
  else if (/^automint:\/\/mini\/?$/.test(url)) dlKind = "mini";
  telemetry.track("deeplink.received", { kind: dlKind });

  const authCallbackMatch = url.match(
    /^automint:\/\/auth\/callback\?token=(.+)/,
  );
  if (authCallbackMatch) {
    const token = decodeURIComponent(authCallbackMatch[1]);
    exchangeDesktopToken(token);
    return;
  }

  const reauthCallbackMatch = url.match(
    /^automint:\/\/reauth\/callback\?token=(.+)/,
  );
  if (reauthCallbackMatch) {
    const token = decodeURIComponent(reauthCallbackMatch[1]);
    exchangeReauthToken(token);
    return;
  }

  if (/^automint:\/\/mini\/?$/.test(url)) {
    createPipWindow();
    return;
  }

  const stripped = url.replace(PROTOCOL + "://", "").replace(/\/$/, "");
  const targetUrl = stripped ? TARGET_URL + "/" + stripped : TARGET_URL;

  if (!isAllowedUrl(targetUrl)) return;

  if (win && !win.isDestroyed()) {
    win.show();
    win.focus();

    if (titlebarView && !titlebarView.webContents.isDestroyed()) {
      titlebarView.webContents.send("deep-link-received", url);
    }

    if (contentView && !contentView.webContents.isDestroyed()) {
      contentView.webContents.loadURL(targetUrl);
    }
  }
}

function createWindow() {
  const saved = getSavedBounds();
  const wasMaximized = store.get("isMaximized", false);

  win = new BaseWindow({
    ...saved,
    minWidth: 900,
    minHeight: 600,
    show: false,
    frame: process.platform === "darwin" ? undefined : false,
    titleBarStyle: process.platform === "darwin" ? "hidden" : undefined,
    icon: path.join(__dirname, "icons", process.platform === "win32" ? "icon.ico" : "icon.png"),

    backgroundColor: process.platform === "win32" ? "#00000000" : "#050505",
    backgroundMaterial: process.platform === "win32" ? "mica" : undefined,
  });

  contentView = new WebContentsView({
    webPreferences: {
      preload: path.join(__dirname, "preload-content.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      partition: "persist:automint",
    },
  });
  win.contentView.addChildView(contentView);

  attachCleanUserAgent(contentView);

  installDesktopFingerprint(contentView.webContents.session);

  contentView.webContents.loadURL(TARGET_URL);

  if (IS_DEV) {
    contentView.webContents.openDevTools({ mode: "detach" });
  }

  contentView.webContents.session.setSpellCheckerEnabled(
    store.get("spellCheck", true),
  );

  attachDesktopBridge(contentView);

  contentView.webContents.session.on("will-download", (_event, item) => {
    const defaultPath = item.getFilename();
    dialog
      .showSaveDialog(win, { defaultPath })
      .then(({ filePath, canceled }) => {
        if (canceled || !filePath) {
          item.cancel();
        } else {
          item.setSavePath(filePath);
        }
      });
  });

  attachContextMenu(contentView, win);

  titlebarView = new WebContentsView({
    webPreferences: {
      preload: path.join(__dirname, "preload-titlebar.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  win.contentView.addChildView(titlebarView);
  titlebarView.setBackgroundColor("#00000000");
  titlebarView.webContents.loadFile(
    path.join(__dirname, "titlebar", "index.html"),
  );

  splashView = new WebContentsView({
    webPreferences: {
      preload: path.join(__dirname, "preload-splash.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  win.contentView.addChildView(splashView);
  splashView.setBackgroundColor("#00000000");
  splashView.webContents.loadFile(path.join(__dirname, "splash", "index.html"));

  wireContentLoadListeners();

  contentView.webContents.session.preconnect({
    url: TARGET_URL,
    numSockets: 2,
  });

  titlebarView.webContents.once("did-finish-load", () => {
    const { net } = require("electron");
    broadcastOnlineStatus(net.isOnline());
  });

  const ALLOWED_PERMISSIONS = [
    "notifications",
    "clipboard-sanitized",
    "storage-access",
  ];

  contentView.webContents.session.setPermissionRequestHandler(
    (_wc, permission, callback) => {
      if (permission === "notifications") {
        const allowed = store.get("notifications", true);
        devLog("permissions", "Request:", permission, "→", allowed ? "granted" : "denied");
        callback(allowed);
      } else if (ALLOWED_PERMISSIONS.includes(permission)) {
        devLog("permissions", "Request:", permission, "→ granted");
        callback(true);
      } else {
        devLog("permissions", "Request:", permission, "→ denied (not in allowlist)");
        callback(false);
      }
    },
  );

  contentView.webContents.session.setPermissionCheckHandler(
    (_wc, permission) => {
      if (permission === "notifications") {
        return store.get("notifications", true);
      }
      return ALLOWED_PERMISSIONS.includes(permission);
    },
  );

  attachNavigationGuards(contentView, win);

  layoutViews();
  win.on("resize", () => {
    layoutViews();
    saveBounds();
  });
  win.on("move", saveBounds);

  win.on("maximize", () => {
    store.set("isMaximized", true);
    titlebarView.webContents.send("window-maximized-changed", true);
  });
  win.on("unmaximize", () => {
    store.set("isMaximized", false);
    titlebarView.webContents.send("window-maximized-changed", false);
  });

  win.on("focus", () => {
    if (titlebarView && !titlebarView.webContents.isDestroyed()) {
      titlebarView.webContents.send("window-focus-changed", true);
    }
  });

  win.on("blur", () => {
    if (titlebarView && !titlebarView.webContents.isDestroyed()) {
      titlebarView.webContents.send("window-focus-changed", false);
    }
  });

  const allRenderers = () =>
    [contentView, titlebarView, splashView]
      .filter((v) => v && v.webContents && !v.webContents.isDestroyed())
      .map((v) => v.webContents);

  win.on("hide", () => {
    for (const wc of allRenderers()) {
      try { wc.setFrameRate(1); } catch {}
      try { wc.setBackgroundThrottling(true); } catch {}
    }
  });

  win.on("show", () => {
    for (const wc of allRenderers()) {
      try { wc.setFrameRate(60); } catch {}
    }
  });

  win.on("close", (e) => {
    if (!isQuitting && store.get("minimizeToTray", true)) {
      e.preventDefault();
      win.hide();
    }
  });

  if (wasMaximized) win.maximize();
  const startHidden = process.argv.includes("--hidden");
  if (!startHidden) win.show();
  layoutViews();
  setImmediate(() => createTray());
}

let pipTitlebarView = null;
let pipContentView = null;

function createPipWindow() {
  if (pipWin && !pipWin.isDestroyed()) {
    pipWin.focus();
    return;
  }

  telemetry.track("pip.opened", {});

  pipWin = new BaseWindow({
    width: 380,
    height: 520,
    minWidth: 300,
    minHeight: 400,
    alwaysOnTop: true,
    frame: false,
    icon: path.join(__dirname, "icons", "icon.png"),
    backgroundColor: "#050505",
  });

  const PIP_TITLEBAR_HEIGHT = 32;

  pipTitlebarView = new WebContentsView({
    webPreferences: {
      preload: path.join(__dirname, "preload-pip.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  pipWin.contentView.addChildView(pipTitlebarView);
  pipTitlebarView.webContents.loadFile(
    path.join(__dirname, "pip", "index.html"),
  );

  pipContentView = new WebContentsView({
    webPreferences: {
      preload: path.join(__dirname, "preload-content.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      partition: "persist:automint",
    },
  });
  pipWin.contentView.addChildView(pipContentView);

  attachCleanUserAgent(pipContentView);
  attachDesktopBridge(pipContentView);
  attachNavigationGuards(pipContentView, pipWin);
  attachContextMenu(pipContentView, pipWin);

  if (IS_DEV) {
    pipContentView.webContents.openDevTools({ mode: "detach" });
  }

  pipContentView.webContents.loadURL(TARGET_URL);

  function layoutPip() {
    if (!pipWin || pipWin.isDestroyed()) return;
    const { width, height } = pipWin.getContentBounds();
    pipTitlebarView.setBounds({
      x: 0,
      y: 0,
      width,
      height: PIP_TITLEBAR_HEIGHT,
    });
    pipContentView.setBounds({
      x: 0,
      y: PIP_TITLEBAR_HEIGHT,
      width,
      height: height - PIP_TITLEBAR_HEIGHT,
    });
  }

  layoutPip();
  pipWin.on("resize", layoutPip);

  pipWin.on("focus", () => {
    if (pipTitlebarView && !pipTitlebarView.webContents.isDestroyed()) {
      pipTitlebarView.webContents.send("window-focus-changed", true);
    }
  });

  pipWin.on("blur", () => {
    if (pipTitlebarView && !pipTitlebarView.webContents.isDestroyed()) {
      pipTitlebarView.webContents.send("window-focus-changed", false);
    }
  });

  pipWin.on("closed", () => {
    pipWin = null;
    pipTitlebarView = null;
    pipContentView = null;
  });
}

function createTray() {
  const icon = nativeImage.createFromPath(
    path.join(__dirname, "icons", "32x32.png"),
  );
  tray = new Tray(icon);
  tray.setToolTip("Automint");

  const contextMenu = Menu.buildFromTemplate([
    {
      label: "Show Automint",
      click: () => {
        win.show();
        win.focus();
      },
    },
    {
      label: "Mini View",
      click: () => createPipWindow(),
    },
    { type: "separator" },
    {
      label: "Quit",
      click: () => {
        isQuitting = true;
        app.quit();
      },
    },
  ]);

  tray.setContextMenu(contextMenu);
  tray.on("click", () => {
    win.show();
    win.focus();
  });
}

function setupJumpList() {
  if (process.platform !== "win32") return;

  const icoPath = asarUnpackedPath("icons", "icon.ico");
  try {
    app.setJumpList([
      {
        type: "tasks",
        items: [
          {
            type: "task",
            title: "Open Mini View",
            description: "Open the floating mini window",
            program: process.execPath,
            args: "automint://mini",
            iconPath: icoPath,
            iconIndex: 0,
          },
          {
            type: "task",
            title: "Dashboard",
            description: "Jump to the dashboard",
            program: process.execPath,
            args: "automint://dashboard",
            iconPath: icoPath,
            iconIndex: 0,
          },
          {
            type: "task",
            title: "Escrow",
            description: "Open escrow listing",
            program: process.execPath,
            args: "automint://escrow",
            iconPath: icoPath,
            iconIndex: 0,
          },
        ],
      },
    ]);
  } catch (err) {
    devError("jump-list", "setJumpList failed:", err);
  }
}

function registerIPC() {
  ipcMain.handle("window-minimize", () => win.minimize());
  ipcMain.handle("window-maximize", () => {
    if (win.isMaximized()) {
      win.unmaximize();
    } else {
      win.maximize();
    }
  });
  ipcMain.handle("window-close", () => win.close());
  ipcMain.handle("window-is-maximized", () => win.isMaximized());

  ipcMain.handle("get-settings", () => ({
    notifications: store.get("notifications", true),
    minimizeToTray: store.get("minimizeToTray", true),
    startOnBoot: store.get("startOnBoot", false),
    spellCheck: store.get("spellCheck", true),
    enableTelemetry: store.get("enableTelemetry", true),
  }));

  ipcMain.handle("set-setting", (_event, key, value) => {
    if (!ALLOWED_SETTING_KEYS.includes(key)) return;
    if (typeof value !== "boolean") return;
    store.set(key, value);

    if (key === "startOnBoot") {
      app.setLoginItemSettings({
        openAtLogin: value,
        args: value ? ["--hidden"] : [],
      });
    }
    if (key === "spellCheck") {
      contentView.webContents.session.setSpellCheckerEnabled(value);
    }
    if (key === "enableTelemetry") {
      telemetry.setConsent(value);
    }
  });

  ipcMain.handle("app-reload", () => {
    contentView.webContents.reload();
  });

  ipcMain.handle("app-clear-session", async () => {
    devLog("session", "Clearing storage data and cache...");
    try {
      const ses = contentView.webContents.session;
      await ses.clearStorageData();
      await ses.clearCache();
      devLog("session", "Cleared — reloading");
      contentView.webContents.loadURL(TARGET_URL);
    } catch (err) {
      devError("session", "Clear failed:", err);
    }
  });

  ipcMain.handle("settings-panel-toggle", (_event, open) => {
    settingsOpen = open;
    layoutViews();
  });

  ipcMain.handle("retry-load", () => {
    contentView.webContents.loadURL(TARGET_URL);
    wireContentLoadListeners();
  });

  ipcMain.handle("splash-done", () => {
    splashActive = false;
    win.contentView.removeChildView(splashView);
    splashView.webContents.close();
    splashView = null;
    layoutViews();
  });

  ipcMain.handle("get-provenance", () => {
    try {
      const p = path.join(__dirname, "build", "provenance.json");
      if (fs.existsSync(p)) {
        return JSON.parse(fs.readFileSync(p, "utf8"));
      }
    } catch {}
    return {
      version: app.getVersion(),
      commit: "",
      shortCommit: "",
      branch: "",
      dirty: false,
      builtAt: "",
    };
  });

  ipcMain.handle("open-privacy-policy", () => {
    safeOpenExternal(TARGET_URL + "/privacy-policy");
  });

  ipcMain.handle("get-desktop-fingerprint", () => {
    return desktopFingerprint.collect();
  });

  ipcMain.handle("open-pip", () => createPipWindow());

  ipcMain.handle("pip-close", () => {
    if (pipWin && !pipWin.isDestroyed()) pipWin.close();
  });

  ipcMain.handle("pip-open-main", () => {
    win.show();
    win.focus();
  });

  ipcMain.handle("set-badge-count", (_event, count) => {
    const num = Math.max(0, Math.floor(count || 0));

    if (process.platform === "win32") {
      if (num === 0) {
        win.setOverlayIcon(null, "");
        return;
      }

      const badgeText = num > 99 ? "99+" : String(num);
      const svg = `<svg width="16" height="16" xmlns="http://www.w3.org/2000/svg">
        <circle cx="8" cy="8" r="8" fill="#e81123"/>
        <text x="8" y="12" text-anchor="middle" font-size="${badgeText.length > 2 ? 7 : 9}" font-family="sans-serif" font-weight="bold" fill="white">${badgeText}</text>
      </svg>`;
      const icon = nativeImage.createFromBuffer(Buffer.from(svg));
      win.setOverlayIcon(icon, `${num} notifications`);
    } else {
      app.setBadgeCount(num);
    }
  });

  ipcMain.handle("show-notification", (_event, title, body) => {
    if (!store.get("notifications", true)) return;
    telemetry.track("notification.shown", {});
    const notif = new Notification({
      title: title || "Automint",
      body: body || "",
      icon: path.join(__dirname, "icons", "icon.png"),
    });
    notif.on("click", () => {
      telemetry.track("notification.clicked", { via: "body" });
      win.show();
      win.focus();
    });
    notif.show();
  });

  const SOUND_TO_GLOW = {
    positive: "positive",
    alert: "alert",
    notification: "notification",
  };

  ipcMain.handle("desktop-sound", (_event, type) => {

    const glowType = SOUND_TO_GLOW[type];
    if (glowType && titlebarView && !titlebarView.webContents.isDestroyed()) {
      titlebarView.webContents.send("titlebar-glow", glowType);
    }
  });

  ipcMain.handle("open-reauth-in-browser", (_event, source) => {
    const reauthUrl = new URL(TARGET_URL + "/reauth");
    reauthUrl.searchParams.set("source", source || "general");
    reauthUrl.searchParams.set(
      "returnTo",
      "/auth/desktop-reauth-callback?source=" + (source || "general"),
    );
    devLog("reauth", "Opening browser for reauth:", reauthUrl.toString());
    safeOpenExternal(reauthUrl.toString());
  });

  ipcMain.handle("desktop-notification", (_event, data) => {
    if (!store.get("notifications", true)) return;
    if (!data || !data.title) return;

    telemetry.track("notification.shown", {});

    const notif = new Notification({
      title: data.title || "Automint",
      body: data.body || "",
      icon: path.join(__dirname, "icons", "icon.png"),
      silent: data.silent || false,
      actions: [{ type: "button", text: "View" }],
      toastXml: buildToastXml(data),
    });

    notif.on("click", () => {
      telemetry.track("notification.clicked", { via: "body" });
      win.show();
      win.focus();
      if (data.url && data.url.startsWith("/") && !data.url.startsWith("//") && contentView && !contentView.webContents.isDestroyed()) {
        contentView.webContents.loadURL(TARGET_URL + data.url);
      }
    });

    notif.on("action", (_event, index) => {
      if (index === 0) {
        telemetry.track("notification.clicked", { via: "action" });

        win.show();
        win.focus();
        if (data.url && data.url.startsWith("/") && !data.url.startsWith("//") && contentView && !contentView.webContents.isDestroyed()) {
          contentView.webContents.loadURL(TARGET_URL + data.url);
        }
      }
    });

    notif.show();
  });
}

function buildToastXml(data) {
  const escXml = (s) =>
    String(s || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  const title = escXml(data.title);
  const body = escXml(data.body);
  const iconPath = path
    .join(__dirname, "icons", "icon.png")
    .replace(/\\/g, "/");

  return `
<toast activationType="protocol">
  <visual>
    <binding template="ToastGeneric">
      <image placement="appLogoOverride" hint-crop="circle" src="file:///${iconPath}"/>
      <text>${title}</text>
      <text>${body}</text>
    </binding>
  </visual>
  <actions>
    <action content="View" activationType="foreground" arguments="view"/>
    <action content="Dismiss" activationType="system" arguments="dismiss"/>
  </actions>
</toast>`.trim();
}

async function captureScreenshot() {
  if (!contentView || contentView.webContents.isDestroyed()) return;

  try {
    const image = await contentView.webContents.capturePage();
    if (image.isEmpty()) return;

    if (titlebarView && !titlebarView.webContents.isDestroyed()) {
      titlebarView.webContents.send("screenshot-flash");
    }

    const { response } = await dialog.showMessageBox(win, {
      type: "question",
      icon: nativeImage.createFromPath(
        path.join(__dirname, "icons", "icon.png"),
      ),
      title: "Screenshot Captured",
      message: "What would you like to do?",
      buttons: ["Save to file", "Copy to clipboard", "Both", "Cancel"],
      defaultId: 2,
      cancelId: 3,
    });

    if (response === 3) return;

    const screenshotAction =
      response === 0 ? "save" : response === 1 ? "copy" : "both";
    telemetry.track("screenshot.captured", { action: screenshotAction });

    const pngBuffer = image.toPNG();

    if (response === 1 || response === 2) {
      clipboard.writeImage(image);
    }

    if (response === 0 || response === 2) {
      const timestamp = new Date()
        .toISOString()
        .replace(/[:.]/g, "-")
        .slice(0, 19);
      const { filePath, canceled } = await dialog.showSaveDialog(win, {
        defaultPath: `automint-${timestamp}.png`,
        filters: [{ name: "PNG Image", extensions: ["png"] }],
      });
      if (!canceled && filePath) {
        fs.writeFileSync(filePath, pngBuffer);
      }
    }
  } catch (err) {
    devError("screenshot", "Capture failed:", err);
  }
}

function registerShortcuts() {
  const viewSubmenu = [
    {
      label: "Reload",
      accelerator: "CmdOrCtrl+R",
      click: () => contentView?.webContents.reload(),
    },
    {
      label: "Toggle Fullscreen",
      accelerator: "F11",
      click: () => win?.setFullScreen(!win.isFullScreen()),
    },
    {
      label: "Mini View",
      accelerator: "CmdOrCtrl+Shift+M",
      click: () => createPipWindow(),
    },
    { type: "separator" },
    {
      label: "Zoom In",
      accelerator: "CmdOrCtrl+=",
      click: () => {
        const level = Math.min(store.get("zoomLevel", 0) + 0.5, 5);
        store.set("zoomLevel", level);
        contentView?.webContents.setZoomLevel(level);
      },
    },
    {
      label: "Zoom Out",
      accelerator: "CmdOrCtrl+-",
      click: () => {
        const level = Math.max(store.get("zoomLevel", 0) - 0.5, -3);
        store.set("zoomLevel", level);
        contentView?.webContents.setZoomLevel(level);
      },
    },
    {
      label: "Reset Zoom",
      accelerator: "CmdOrCtrl+0",
      click: () => {
        store.set("zoomLevel", 0);
        contentView?.webContents.setZoomLevel(0);
      },
    },
  ];

  if (IS_DEV) {
    viewSubmenu.push(
      { type: "separator" },
      {
        label: "Toggle DevTools (Content)",
        accelerator: "CmdOrCtrl+Shift+I",
        click: () => contentView?.webContents.toggleDevTools({ mode: "detach" }),
      },
      {
        label: "Toggle DevTools (Titlebar)",
        accelerator: "CmdOrCtrl+Shift+T",
        click: () => titlebarView?.webContents.toggleDevTools({ mode: "detach" }),
      },
    );
  }

  const screenshotSubmenu = [
    {
      label: "Capture Screenshot",
      accelerator: "CmdOrCtrl+Shift+S",
      click: () => captureScreenshot(),
    },
  ];

  const template = [
    { label: "View", submenu: viewSubmenu },
    { label: "Tools", submenu: screenshotSubmenu },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

app.setName("Automint");
if (process.platform === "win32") {
  app.setAppUserModelId("org.automint.desktop");
}

if (IS_DEV) {

  if (
    !app.isDefaultProtocolClient(PROTOCOL, process.execPath, [
      path.resolve("."),
    ])
  ) {
    const ok = app.setAsDefaultProtocolClient(PROTOCOL, process.execPath, [
      path.resolve("."),
    ]);
    devLog("protocol", "Registered", PROTOCOL + "://", "→", ok ? "success" : "FAILED");
  } else {
    devLog("protocol", PROTOCOL + "://", "already registered");
  }
} else {
  if (!app.isDefaultProtocolClient(PROTOCOL)) {
    app.setAsDefaultProtocolClient(PROTOCOL);
  }
}

app.on("second-instance", (_event, argv) => {
  devLog("deep-link", "second-instance argv:", argv.join(" "));
  const deepLinkUrl = argv.find((arg) => arg.startsWith(PROTOCOL + "://"));
  if (deepLinkUrl) {
    handleDeepLink(deepLinkUrl);
  } else {
    devLog("deep-link", "No deep link in argv — focusing window");
    if (win && !win.isDestroyed()) {
      win.show();
      win.focus();
    }
  }
});

app.on("open-url", (event, url) => {
  event.preventDefault();
  if (app.isReady()) {
    handleDeepLink(url);
  } else {
    app.whenReady().then(() => handleDeepLink(url));
  }
});

async function verifyIntegrity() {
  if (IS_DEV) return;

  try {

    const asarPath = path.join(path.dirname(app.getAppPath()), "app.asar");
    if (!fs.existsSync(asarPath)) return;

    const hash = await new Promise((resolve, reject) => {
      const sha = crypto.createHash("sha256");
      const stream = require("original-fs").createReadStream(asarPath);
      stream.on("data", (chunk) => sha.update(chunk));
      stream.on("end", () => resolve(sha.digest("hex")));
      stream.on("error", reject);
    });

    const { net } = require("electron");
    const response = await net.fetch(UPDATE_URL + "/integrity.json");
    if (!response.ok) return;

    const data = await response.json();
    const expected = data[process.platform];
    if (!expected) return;

    if (hash !== expected) {
      dialog
        .showMessageBox({
          type: "warning",
          icon: nativeImage.createFromPath(
            path.join(__dirname, "icons", "icon.png"),
          ),
          title: "Security Warning",
          message: "Automint may have been tampered with.",
          detail:
            "The app's integrity check failed. This could mean the installation was modified by a third party.\n\n" +
            "For your safety, please download the official version from example.com.",
          buttons: ["Download Official Version", "Continue Anyway"],
          defaultId: 0,
          cancelId: 1,
          noLink: true,
        })
        .then(({ response }) => {
          if (response === 0) {
            shell.openExternal("https://example.com/download");
            isQuitting = true;
            app.quit();
          }
        });
    }
  } catch {

  }
}

function setupAutoUpdater() {
  if (IS_DEV) return;

  autoUpdater.setFeedURL({ provider: "generic", url: UPDATE_URL });
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;

  autoUpdater.on("update-available", (info) => {
    telemetry.track("update.available", { version: info.version });
    new Notification({
      title: "Update Available",
      body: `Automint v${info.version} is downloading...`,
      icon: path.join(__dirname, "icons", "icon.png"),
    }).show();
  });

  autoUpdater.on("update-downloaded", () => {
    telemetry.track("update.downloaded", {});
    dialog
      .showMessageBox(win, {
        type: "info",
        icon: nativeImage.createFromPath(
          path.join(__dirname, "icons", "icon.png"),
        ),
        title: "Update Ready",
        message: "A new version of Automint is ready to install.",
        detail: "The app will restart to apply the update.",
        buttons: ["Restart now", "Later"],
        defaultId: 0,
        cancelId: 1,
      })
      .then(({ response }) => {
        if (response === 0) {
          isQuitting = true;
          autoUpdater.quitAndInstall();
        }
      });
  });

  autoUpdater.on("error", (err) => {
    telemetry.track("update.error", { code: err && err.code ? err.code : "" });
    devError("updater", "Error:", err);
  });

  autoUpdater.checkForUpdates().catch((err) => devError("updater", "Check failed:", err));
  setInterval(
    () => {
      autoUpdater.checkForUpdates().catch((err) => devError("updater", "Check failed:", err));
    },
    4 * 60 * 60 * 1000,
  );
}

app.whenReady().then(() => {
  devLog("app", "Ready — IS_DEV:", IS_DEV, "TARGET_URL:", TARGET_URL);
  devLog("app", "argv:", process.argv.join(" "));
  telemetry.init();
  telemetry.trackCore("app.session_start", {
    hadDeepLink: process.argv.some((a) => a.startsWith(PROTOCOL + "://")),
    startHidden: process.argv.includes("--hidden"),
  });
  registerIPC();
  registerShortcuts();
  createWindow();

  globalShortcut.register("CmdOrCtrl+Shift+A", () => {
    if (win && !win.isDestroyed()) {
      win.show();
      win.focus();
    }
  });

  let deferredStartupRan = false;
  const runDeferredStartup = () => {
    if (deferredStartupRan) return;
    deferredStartupRan = true;
    setupAutoUpdater();
    verifyIntegrity();
    startConnectionQualityMonitor();
    setupJumpList();
    const { net } = require("electron");
    setInterval(() => {
      broadcastOnlineStatus(net.isOnline());
    }, 5000);
    setTimeout(() => telemetry.flush(), 20000);
    setInterval(() => telemetry.flush(), 5 * 60 * 1000);
    setInterval(
      () =>
        telemetry.trackCore("app.heartbeat", {
          focused: win && !win.isDestroyed() ? win.isFocused() : false,
        }),
      30 * 60 * 1000,
    );
  };

  if (contentView && !contentView.webContents.isDestroyed()) {
    contentView.webContents.once("did-finish-load", () => {
      setTimeout(runDeferredStartup, 2000);
    });
    contentView.webContents.once("did-fail-load", () => {

      setTimeout(runDeferredStartup, 2000);
    });
  } else {

    setTimeout(runDeferredStartup, 3000);
  }

  const launchUrl = process.argv.find((arg) =>
    arg.startsWith(PROTOCOL + "://"),
  );
  if (launchUrl) {
    devLog("deep-link", "Cold start with deep link:", launchUrl);

    setTimeout(() => handleDeepLink(launchUrl), 1500);
  } else {
    devLog("deep-link", "No deep link in launch args");
  }

  app.on("activate", () => {
    if (!win || win.isDestroyed()) {
      createWindow();
    } else {
      win.show();
    }
  });
});

app.on("before-quit", () => {
  isQuitting = true;
  globalShortcut.unregisterAll();
  telemetry.endSession();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});
