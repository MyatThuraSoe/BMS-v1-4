import apiClient from '../apiClient';

// Phase 2: customer-specific price lists.

export const priceListService = {
  getAll: async () => {
    const response = await apiClient.get('/price-lists');
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/price-lists/${id}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/price-lists', data);
    return response.data;
  },

  update: async (id, data) => {
    const response = await apiClient.put(`/price-lists/${id}`, data);
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/price-lists/${id}`);
    return response.data;
  },
};
