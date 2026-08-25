import apiClient from '../apiClient';

// Auto-split from services.js — domain: procurement

export const supplierService = {
  getAll: async (page = 0, size = 20) => {
    const response = await apiClient.get(`/suppliers?page=${page}&size=${size}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/suppliers/${id}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/suppliers', data);
    return response.data;
  },

  update: async (id, data) => {
    const response = await apiClient.put(`/suppliers/${id}`, data);
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/suppliers/${id}`);
    return response.data;
  },

  search: async (query) => {
    const response = await apiClient.get(`/suppliers/search?query=${encodeURIComponent(query)}`);
    return response.data;
  },
};

export const purchaseService = {
  getAll: async (page = 0, size = 20, sortBy = 'purchaseDate') => {
    const response = await apiClient.get(`/purchases?page=${page}&size=${size}&sortBy=${sortBy}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/purchases/${id}`);
    return response.data;
  },

  getByNumber: async (purchaseNumber) => {
    const response = await apiClient.get(`/purchases/number/${purchaseNumber}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/purchases', data);
    return response.data;
  },

  updatePaymentStatus: async (id, paymentStatus) => {
    const response = await apiClient.patch(`/purchases/${id}/payment-status`, { paymentStatus });
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/purchases/${id}`);
    return response.data;
  },

  getSupplierStats: async (supplierId) => {
    const response = await apiClient.get(`/purchases/supplier/${supplierId}/stats`);
    return response.data;
  },

  getSupplierTopProducts: async (supplierId) => {
    const response = await apiClient.get(`/purchases/supplier/${supplierId}/top-products`);
    return response.data;
  },
};
