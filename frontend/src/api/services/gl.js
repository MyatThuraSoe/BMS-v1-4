import apiClient from '../apiClient';

// Phase 2: chart of accounts, event mappings and journal export.

export const glService = {
  getAccounts: async () => {
    const response = await apiClient.get('/gl/accounts');
    return response.data;
  },

  createAccount: async (data) => {
    const response = await apiClient.post('/gl/accounts', data);
    return response.data;
  },

  updateAccount: async (id, data) => {
    const response = await apiClient.put(`/gl/accounts/${id}`, data);
    return response.data;
  },

  getMappings: async () => {
    const response = await apiClient.get('/gl/mappings');
    return response.data;
  },

  saveMapping: async (data) => {
    const response = await apiClient.post('/gl/mappings', data);
    return response.data;
  },

  deleteMapping: async (id) => {
    const response = await apiClient.delete(`/gl/mappings/${id}`);
    return response.data;
  },

  getJournal: async ({ from = '', to = '' } = {}) => {
    const params = new URLSearchParams();
    if (from) params.append('from', from);
    if (to) params.append('to', to);
    const response = await apiClient.get(`/gl/journal?${params.toString()}`);
    return response.data;
  },

  exportJournal: async ({ from = '', to = '' } = {}) => {
    const params = new URLSearchParams();
    if (from) params.append('from', from);
    if (to) params.append('to', to);
    const response = await apiClient.get(`/gl/journal/export?${params.toString()}`, {
      responseType: 'blob',
    });
    return response;
  },
};
