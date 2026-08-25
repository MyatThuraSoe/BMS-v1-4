import apiClient from '../apiClient';

// Auto-split from services.js — domain: system

export const systemSettingService = {
  getAll: async () => {
    const response = await apiClient.get('/settings');
    return response.data;
  },

  getByKey: async (key) => {
    const response = await apiClient.get(`/settings/key/${key}`);
    return response.data;
  },

  update: async (key, data) => {
    const response = await apiClient.put(`/settings/key/${key}`, data);
    return response.data;
  },
};
