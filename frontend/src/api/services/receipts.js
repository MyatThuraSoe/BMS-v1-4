import apiClient from '../apiClient';

// Auto-split from services.js — domain: receipts

export const receiptService = {
  getByInvoiceNumber: async (invoiceNumber) => {
    const response = await apiClient.get(`/receipts/invoice/${invoiceNumber}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/receipts/${id}`);
    return response.data;
  },

  getPrintHtml: async (invoiceNumber) => {
    const response = await apiClient.get(`/receipts/invoice/${invoiceNumber}/print`, {
      headers: { 'Accept': 'text/html' },
    });
    return response.data;
  },

  downloadReceipt: async (invoiceNumber, format) => {
    const response = await apiClient.get(`/receipts/invoice/${invoiceNumber}/${format}`, {
      responseType: 'blob',
    });
    return response.data;
  },
};

export const counterPrintService = {
  // Print a receipt at the counter printer attached to the server computer.
  printReceipt: async (invoiceNumber) => {
    const response = await apiClient.post(`/counter-print/receipt/${encodeURIComponent(invoiceNumber)}`, null, { timeout: 10000 });
    return response.data;
  },

  claimNextReceipt: async () => {
    const response = await apiClient.get('/counter-print/receipt-jobs/next');
    return response.data;
  },

  completeReceipt: async (jobId, success) => {
    const response = await apiClient.post(`/counter-print/receipt-jobs/${encodeURIComponent(jobId)}/complete`, { success }, { timeout: 10000 });
    return response.data;
  },

  listPrinters: async () => {
    const response = await apiClient.get('/counter-print/printers');
    return response.data;
  },

  getConfig: async () => {
    const response = await apiClient.get('/counter-print/config');
    return response.data;
  },

  saveConfig: async (printerName) => {
    const response = await apiClient.put('/counter-print/config', { printerName });
    return response.data;
  },

  testPrint: async (printerName) => {
    const response = await apiClient.post('/counter-print/test', { printerName: printerName || null });
    return response.data;
  },
};

export const shopInfoService = {
  get: async () => {
    const response = await apiClient.get('/shop-info');
    return response.data;
  },

  update: async (data) => {
    const response = await apiClient.put('/shop-info', data);
    return response.data;
  },

  getReceiptCustomization: async () => {
    const response = await apiClient.get('/receipt-customization');
    return response.data;
  },

  saveReceiptCustomization: async (data) => {
    const response = await apiClient.put('/receipt-customization', data);
    return response.data;
  },

  getLogo: async () => {
    const response = await apiClient.get('/shop-info/logo', { responseType: 'blob' });
    return response.data;
  },

  uploadLogo: async (file) => {
    const formData = new FormData();
    formData.append('file', file);
    const response = await apiClient.post('/shop-info/logo', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return response.data;
  },

  deleteLogo: async () => {
    const response = await apiClient.delete('/shop-info/logo');
    return response.data;
  },
};

export const receiptCustomizationService = {
  get: async () => {
    const response = await apiClient.get('/receipt-customization');
    return response.data;
  },

  upsert: async (data) => {
    const response = await apiClient.put('/receipt-customization', data);
    return response.data;
  },
};
