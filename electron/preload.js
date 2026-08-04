const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    platform: process.platform,
    version: process.versions.electron,
    quitApp: () => ipcRenderer.send('quit-app')
});