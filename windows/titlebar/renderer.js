const api = window.titlebar;

const btnMinimize = document.getElementById("btn-minimize");
const btnMaximize = document.getElementById("btn-maximize");
const btnClose = document.getElementById("btn-close");
const iconMaximize = document.getElementById("icon-maximize");
const iconRestore = document.getElementById("icon-restore");

btnMinimize.addEventListener("click", () => api.minimize());
btnMaximize.addEventListener("click", () => api.maximize());
btnClose.addEventListener("click", () => api.close());

function updateMaximizeIcon(isMaximized) {
  iconMaximize.style.display = isMaximized ? "none" : "block";
  iconRestore.style.display = isMaximized ? "block" : "none";
}

api.isMaximized().then(updateMaximizeIcon);
api.onMaximizedChanged(updateMaximizeIcon);

const btnSettings = document.getElementById("btn-settings");
const settingsBackdrop = document.getElementById("settings-backdrop");
const settingsPanel = document.getElementById("settings-panel");
const toggleNotifications = document.getElementById("toggle-notifications");
const toggleSpellcheck = document.getElementById("toggle-spellcheck");
const toggleTray = document.getElementById("toggle-tray");
const toggleBoot = document.getElementById("toggle-boot");
const toggleTelemetry = document.getElementById("toggle-telemetry");
const linkPrivacy = document.getElementById("link-privacy");
const btnReload = document.getElementById("btn-reload");
const btnClearSession = document.getElementById("btn-clear-session");

function openSettings() {
  settingsBackdrop.classList.remove("hidden");
  settingsBackdrop.classList.remove("closing");
  btnSettings.classList.add("active");
  api.setSettingsOpen(true);
}

function closeSettings() {
  settingsBackdrop.classList.add("closing");
  settingsBackdrop.addEventListener(
    "animationend",
    () => {
      settingsBackdrop.classList.add("hidden");
      settingsBackdrop.classList.remove("closing");
    },
    { once: true },
  );
  btnSettings.classList.remove("active");
  api.setSettingsOpen(false);
}

btnSettings.addEventListener("click", (e) => {
  e.stopPropagation();
  const isHidden = settingsBackdrop.classList.contains("hidden");
  isHidden ? openSettings() : closeSettings();
});

settingsBackdrop.addEventListener("click", () => closeSettings());
settingsPanel.addEventListener("click", (e) => e.stopPropagation());

api.getSettings().then((settings) => {
  toggleNotifications.checked = settings.notifications !== false;
  toggleSpellcheck.checked = settings.spellCheck !== false;
  toggleTray.checked = settings.minimizeToTray !== false;
  toggleBoot.checked = settings.startOnBoot === true;
  toggleTelemetry.checked = settings.enableTelemetry !== false;
});

function flashRow(toggle) {
  const row = toggle.closest(".settings-row");
  if (!row) return;
  row.classList.remove("flash");
  void row.offsetWidth;
  row.classList.add("flash");
}

toggleNotifications.addEventListener("change", () => {
  api.setSetting("notifications", toggleNotifications.checked);
  flashRow(toggleNotifications);
});

toggleSpellcheck.addEventListener("change", () => {
  api.setSetting("spellCheck", toggleSpellcheck.checked);
  flashRow(toggleSpellcheck);
});

toggleTray.addEventListener("change", () => {
  api.setSetting("minimizeToTray", toggleTray.checked);
  flashRow(toggleTray);
});

toggleBoot.addEventListener("change", () => {
  api.setSetting("startOnBoot", toggleBoot.checked);
  flashRow(toggleBoot);
});

toggleTelemetry.addEventListener("change", () => {
  api.setSetting("enableTelemetry", toggleTelemetry.checked);
  flashRow(toggleTelemetry);
});

linkPrivacy.addEventListener("click", (e) => {
  e.preventDefault();
  api.openPrivacy();
});

btnReload.addEventListener("click", () => api.reload());

api.getProvenance().then((info) => {
  const versionEl = document.getElementById("prov-version");
  const commitEl = document.getElementById("prov-commit");
  const builtEl = document.getElementById("prov-built");
  if (versionEl) versionEl.textContent = info.version || "—";
  if (commitEl) {
    const short = info.shortCommit || "—";
    commitEl.textContent = short;
    if (info.commit) commitEl.title = info.commit;
    if (info.dirty) commitEl.classList.add("dirty");
  }
  if (builtEl) {
    if (info.builtAt) {
      const d = new Date(info.builtAt);
      builtEl.textContent = isNaN(d.getTime())
        ? info.builtAt
        : d.toISOString().slice(0, 10);
    } else {
      builtEl.textContent = "—";
    }
  }
});

const confirmBackdrop = document.getElementById("confirm-backdrop");
const confirmCancel = document.getElementById("confirm-cancel");
const confirmClear = document.getElementById("confirm-clear");

function openConfirmDialog() {
  confirmBackdrop.classList.remove("hidden");
  confirmBackdrop.classList.remove("closing");
}

function closeConfirmDialog() {
  confirmBackdrop.classList.add("closing");
  confirmBackdrop.addEventListener(
    "animationend",
    () => {
      confirmBackdrop.classList.add("hidden");
      confirmBackdrop.classList.remove("closing");
    },
    { once: true },
  );
}

function isConfirmDialogOpen() {
  return (
    !confirmBackdrop.classList.contains("hidden") &&
    !confirmBackdrop.classList.contains("closing")
  );
}

function isSettingsOpen() {
  return (
    !settingsBackdrop.classList.contains("hidden") &&
    !settingsBackdrop.classList.contains("closing")
  );
}

btnClearSession.addEventListener("click", () => openConfirmDialog());

confirmCancel.addEventListener("click", () => closeConfirmDialog());

confirmClear.addEventListener("click", () => {
  closeConfirmDialog();
  api.clearSession();
});

confirmBackdrop.addEventListener("click", () => closeConfirmDialog());
document
  .getElementById("confirm-dialog")
  .addEventListener("click", (e) => e.stopPropagation());

const titlebarEl = document.querySelector(".titlebar");
let glowTimeout = null;

api.onGlow((type) => {

  titlebarEl.classList.remove(
    "glow-positive",
    "glow-alert",
    "glow-notification",
    "glow-error",
  );
  clearTimeout(glowTimeout);

  void titlebarEl.offsetWidth;

  const glowClass = "glow-" + type;
  titlebarEl.classList.add(glowClass);

  glowTimeout = setTimeout(() => {
    titlebarEl.classList.remove(glowClass);
  }, 2100);
});

const connectionIndicator = document.getElementById("connection-indicator");

const QUALITY_LABELS = {
  good: "Connection: excellent",
  medium: "Connection: moderate latency",
  poor: "Connection: high latency",
  offline: "Connection: offline",
};

api.onConnectionQuality((quality) => {
  connectionIndicator.className = "connection-indicator " + quality;
  connectionIndicator.title = QUALITY_LABELS[quality] || "Connection: unknown";
});

const deepLinkOverlay = document.getElementById("deep-link-overlay");

api.onDeepLink((url) => {

  const path = url.replace("automint://", "/").replace(/\/$/, "") || "/";
  const label = deepLinkOverlay.querySelector(".deep-link-path");
  if (label) label.textContent = path;

  deepLinkOverlay.classList.remove("hidden");
  deepLinkOverlay.classList.remove("closing");

  setTimeout(() => {
    deepLinkOverlay.classList.add("closing");
    deepLinkOverlay.addEventListener(
      "animationend",
      () => {
        deepLinkOverlay.classList.add("hidden");
        deepLinkOverlay.classList.remove("closing");
      },
      { once: true },
    );
  }, 1800);
});

const offlineBanner = document.getElementById("offline-banner");

api.onOnlineStatusChanged((online) => {
  if (online) {
    offlineBanner.classList.add("closing");
    offlineBanner.addEventListener(
      "animationend",
      () => {
        offlineBanner.classList.add("hidden");
        offlineBanner.classList.remove("closing");
      },
      { once: true },
    );
  } else {
    offlineBanner.classList.remove("hidden");
    offlineBanner.classList.remove("closing");
  }
});

const screenshotFlash = document.getElementById("screenshot-flash");

api.onScreenshotFlash(() => {
  screenshotFlash.classList.remove("hidden");
  screenshotFlash.addEventListener(
    "animationend",
    () => screenshotFlash.classList.add("hidden"),
    { once: true },
  );
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") {
    if (isConfirmDialogOpen()) {
      closeConfirmDialog();
    } else if (isSettingsOpen()) {
      closeSettings();
    }
  }
});

api.onWindowFocusChanged((focused) => {
  if (focused) {
    document.body.classList.add("window-focused");
  } else {
    document.body.classList.remove("window-focused");
  }
});
