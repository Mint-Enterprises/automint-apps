const Sentry = require("@sentry/electron/renderer");

Sentry.init();

const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("automint", {
  platform: process.platform,
  isDesktop: true,
  showNotification: (title, body) =>
    ipcRenderer.invoke("show-notification", title, body),
  setBadgeCount: (count) => ipcRenderer.invoke("set-badge-count", count),

  openReauthInBrowser: (source) =>
    ipcRenderer.invoke("open-reauth-in-browser", source),

  getDesktopFingerprint: () => ipcRenderer.invoke("get-desktop-fingerprint"),

  _soundPlayed: (type) => ipcRenderer.invoke("desktop-sound", type),
  _notificationShown: (data) =>
    ipcRenderer.invoke("desktop-notification", data),
});
