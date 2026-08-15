const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    platform: process.platform,
    version: process.versions.electron,
    isElectron: true,
    quitApp: () => ipcRenderer.send('quit-app'),
    openExternal: (url) => ipcRenderer.invoke('open-external', url),

    // ===== Direct silent printing (Technique #4 from PDF) =====
    getPrinters: () => ipcRenderer.invoke('get-printers'),
    printReceipt: (html, printerName, paperSizeMm) => ipcRenderer.invoke('print-receipt', html, printerName, paperSizeMm)
});