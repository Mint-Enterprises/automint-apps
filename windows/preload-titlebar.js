const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("titlebar", {
  minimize: () => ipcRenderer.invoke("window-minimize"),
  maximize: () => ipcRenderer.invoke("window-maximize"),
  close: () => ipcRenderer.invoke("window-close"),
  isMaximized: () => ipcRenderer.invoke("window-is-maximized"),
  onMaximizedChanged: (callback) => {
    const handler = (_event, value) => callback(value);
    ipcRenderer.on("window-maximized-changed", handler);
    return () => ipcRenderer.off("window-maximized-changed", handler);
  },
  getSettings: () => ipcRenderer.invoke("get-settings"),
  setSetting: (key, value) => ipcRenderer.invoke("set-setting", key, value),
  reload: () => ipcRenderer.invoke("app-reload"),
  clearSession: () => ipcRenderer.invoke("app-clear-session"),
  getProvenance: () => ipcRenderer.invoke("get-provenance"),
  openPrivacy: () => ipcRenderer.invoke("open-privacy-policy"),
  setSettingsOpen: (open) => ipcRenderer.invoke("settings-panel-toggle", open),
  openPip: () => ipcRenderer.invoke("open-pip"),
  onOnlineStatusChanged: (callback) => {
    const handler = (_event, online) => callback(online);
    ipcRenderer.on("online-status-changed", handler);
    return () => ipcRenderer.off("online-status-changed", handler);
  },
  onWindowFocusChanged: (callback) => {
    const handler = (_event, focused) => callback(focused);
    ipcRenderer.on("window-focus-changed", handler);
    return () => ipcRenderer.off("window-focus-changed", handler);
  },
  onGlow: (callback) => {
    const handler = (_event, type) => callback(type);
    ipcRenderer.on("titlebar-glow", handler);
    return () => ipcRenderer.off("titlebar-glow", handler);
  },
  onConnectionQuality: (callback) => {
    const handler = (_event, quality) => callback(quality);
    ipcRenderer.on("connection-quality", handler);
    return () => ipcRenderer.off("connection-quality", handler);
  },
  onDeepLink: (callback) => {
    const handler = (_event, url) => callback(url);
    ipcRenderer.on("deep-link-received", handler);
    return () => ipcRenderer.off("deep-link-received", handler);
  },
  onScreenshotFlash: (callback) => {
    const handler = () => callback();
    ipcRenderer.on("screenshot-flash", handler);
    return () => ipcRenderer.off("screenshot-flash", handler);
  },
});
