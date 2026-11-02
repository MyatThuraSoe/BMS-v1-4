import apiClient from '../apiClient';

// Phase 2: ABC cycle counts, blind counting, variance approval and offline sync.

export const stockCountService = {
  getAll: async ({ status = '', page = 0, size = 20 } = {}) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) params.append('status', status);
    const response = await apiClient.get(`/stock-counts?${params.toString()}`);
    return response.data;
  },

  getById: async (id, { blind = false } = {}) => {
    const response = await apiClient.get(`/stock-counts/${id}?blind=${blind}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/stock-counts', data);
    return response.data;
  },

  generateDue: async () => {
    const response = await apiClient.post('/stock-counts/generate');
    return response.data;
  },

  refreshAbc: async () => {
    const response = await apiClient.post('/stock-counts/refresh-abc');
    return response.data;
  },

  start: async (id) => {
    const response = await apiClient.post(`/stock-counts/${id}/start`);
    return response.data;
  },

  submit: async (id, data) => {
    const response = await apiClient.post(`/stock-counts/${id}/submit`, data);
    return response.data;
  },

  approve: async (id) => {
    const response = await apiClient.post(`/stock-counts/${id}/approve`);
    return response.data;
  },

  reject: async (id, reason) => {
    const response = await apiClient.post(`/stock-counts/${id}/reject`, { reason });
    return response.data;
  },

  getReasonCodes: async () => {
    const response = await apiClient.get('/stock-counts/reason-codes');
    return response.data;
  },

  sync: async (data) => {
    const response = await apiClient.post('/sync/count-sheet', data);
    return response.data;
  },
};
