// Direct silent printing via Electron (Technique #4 from PDF)
// Works offline with any installed Windows printer - no QZ Tray needed
const directPrint = {
    isAvailable: () => !!(window.electronAPI && window.electronAPI.printReceipt),

    getPrinters: async () => {
        if (!directPrint.isAvailable()) return [];
        return await window.electronAPI.getPrinters();
    },

    print: async (html, printerName, paperSizeMm) => {
        if (!directPrint.isAvailable()) {
            throw new Error('Direct printing only works in the desktop app.');
        }
        return await window.electronAPI.printReceipt(html, printerName, paperSizeMm);
    }
};

export default directPrint;