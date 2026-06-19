const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("splash", {
  done: () => ipcRenderer.invoke("splash-done"),
  retry: () => ipcRenderer.invoke("retry-load"),
  onWebsiteReady: (callback) =>
    ipcRenderer.on("website-ready", () => callback()),
  onWebsiteFailed: (callback) =>
    ipcRenderer.on("website-failed", (_e, errorCode, errorDesc) =>
      callback(errorCode, errorDesc),
    ),
});
