import apiClient from '../apiClient';

// Auto-split from services.js — domain: customers

export const customerService = {
  getAll: async (page = 0, size = 20) => {
    const response = await apiClient.get(`/customers?page=${page}&size=${size}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/customers/${id}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/customers', data);
    return response.data;
  },

  update: async (id, data) => {
    const response = await apiClient.put(`/customers/${id}`, data);
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/customers/${id}`);
    return response.data;
  },

  search: async (keyword, page = 0, size = 20, city = '') => {
      const response = await apiClient.get(
          `/customers/search?keyword=${encodeURIComponent(keyword || '')}&city=${encodeURIComponent(city || '')}&page=${page}&size=${size}`
      );
      return response.data;
  },

  getCities: async () => {
      const response = await apiClient.get('/customers/cities');
      return response.data;
  },
};

export const arService = {
  getOutstanding: async (page = 0, size = 20, keyword = '') => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (keyword) params.append('keyword', keyword);
    const response = await apiClient.get(`/ar/outstanding?${params.toString()}`);
    return response.data;
  },

  getCustomerHistory: async (customerId, page = 0, size = 20) => {
    const response = await apiClient.get(`/ar/customer/${customerId}?page=${page}&size=${size}`);
    return response.data;
  },

  recordPayment: async (invoiceId, data) => {
    const response = await apiClient.post(`/ar/${invoiceId}/payment`, data);
    return response.data;
  },

  getInvoicePayments: async (invoiceId) => {
    const response = await apiClient.get(`/ar/${invoiceId}/payments`);
    return response.data;
  },

  getPaymentPrintHtml: async (paymentId) => {
    const response = await apiClient.get(`/receipts/ar-payment/${paymentId}/print`, {
      headers: { 'Accept': 'text/html' },
    });
    return response.data;
  },
};
