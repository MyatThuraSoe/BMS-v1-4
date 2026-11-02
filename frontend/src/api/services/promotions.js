import apiClient from '../apiClient';

// Phase 2: dynamic promotions (percent/fixed/BOGO/tiered/flash sale).

export const promotionService = {
  getAll: async () => {
    const response = await apiClient.get('/promotions');
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/promotions/${id}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/promotions', data);
    return response.data;
  },

  update: async (id, data) => {
    const response = await apiClient.put(`/promotions/${id}`, data);
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/promotions/${id}`);
    return response.data;
  },
};
