import apiClient from '../apiClient';

// Auto-split from services.js — domain: expenses

export const expenseService = {
  getAll: async (month = null, year = null) => {
    const params = new URLSearchParams();
    if (month) params.append('startDate', `${year || new Date().getFullYear()}-${String(month).padStart(2, '0')}-01`);
    if (month) params.append('endDate', `${year || new Date().getFullYear()}-${String(month).padStart(2, '0')}-${new Date(year || new Date().getFullYear(), month, 0).getDate()}`);
    const response = await apiClient.get(`/expenses${params.toString() ? `?${params.toString()}` : ''}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/expenses', data);
    return response.data;
  },

  update: async (id, data) => {
    const response = await apiClient.put(`/expenses/${id}`, data);
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/expenses/${id}`);
    return response.data;
  },

  getReceiptImage: async (id) => {
    const response = await apiClient.get(`/expenses/${id}/receipt-image`, {
      responseType: 'blob',
    });
    return response.data;
  },

  uploadReceiptImage: async (id, file) => {
    const formData = new FormData();
    formData.append('file', file);
    const response = await apiClient.post(`/expenses/${id}/receipt-image`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return response.data;
  },

  deleteReceiptImage: async (id) => {
    const response = await apiClient.delete(`/expenses/${id}/receipt-image`);
    return response.data;
  },
};
