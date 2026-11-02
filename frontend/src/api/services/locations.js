import apiClient from '../apiClient';

// Phase 2: multi-location stock, put-away, bin scanning, rescue and transfers.

export const locationService = {
  getAll: async () => {
    const response = await apiClient.get('/locations');
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/locations', data);
    return response.data;
  },

  update: async (id, data) => {
    const response = await apiClient.put(`/locations/${id}`, data);
    return response.data;
  },

  getStock: async (id) => {
    const response = await apiClient.get(`/locations/${id}/stock`);
    return response.data;
  },

  getPutAwayList: async () => {
    const response = await apiClient.get('/locations/put-away');
    return response.data;
  },

  getBinLabels: async (includeUnassigned = false) => {
    const response = await apiClient.get(`/locations/bin-labels?includeUnassigned=${includeUnassigned}`);
    return response.data;
  },

  scan: async (code) => {
    const response = await apiClient.get(`/locations/scan?code=${encodeURIComponent(code)}`);
    return response.data;
  },

  rescue: async (data) => {
    const response = await apiClient.post('/locations/rescue', data);
    return response.data;
  },

  getTransfers: async ({ page = 0, size = 20 } = {}) => {
    const response = await apiClient.get(`/locations/transfers?page=${page}&size=${size}`);
    return response.data;
  },

  getTransfer: async (id) => {
    const response = await apiClient.get(`/locations/transfers/${id}`);
    return response.data;
  },

  createTransfer: async (data) => {
    const response = await apiClient.post('/locations/transfers', data);
    return response.data;
  },

  startTransfer: async (id) => {
    const response = await apiClient.post(`/locations/transfers/${id}/start`);
    return response.data;
  },

  receiveTransfer: async (id) => {
    const response = await apiClient.post(`/locations/transfers/${id}/receive`);
    return response.data;
  },

  completeTransfer: async (id) => {
    const response = await apiClient.post(`/locations/transfers/${id}/complete`);
    return response.data;
  },

  cancelTransfer: async (id) => {
    const response = await apiClient.post(`/locations/transfers/${id}/cancel`);
    return response.data;
  },
};
