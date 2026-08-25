import apiClient from '../apiClient';

// Auto-split from services.js — domain: orders

export const orderService = {
  getAll: async (params = {}) => {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '' && v !== false) query.append(k, v);
    });
    const response = await apiClient.get(`/orders?${query.toString()}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/orders/${id}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/orders', data);
    return response.data;
  },

  convert: async (id, data = {}) => {
    const response = await apiClient.post(`/orders/${id}/convert`, data);
    return response.data;
  },

  cancel: async (id, data = {}) => {
    const response = await apiClient.post(`/orders/${id}/cancel`, data);
    return response.data;
  },
};
