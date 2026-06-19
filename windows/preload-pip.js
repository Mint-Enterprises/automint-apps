const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("pip", {
  close: () => ipcRenderer.invoke("pip-close"),
  openMain: () => ipcRenderer.invoke("pip-open-main"),
  onWindowFocusChanged: (callback) => {
    const handler = (_event, focused) => callback(focused);
    ipcRenderer.on("window-focus-changed", handler);
    return () => ipcRenderer.off("window-focus-changed", handler);
  },
});
