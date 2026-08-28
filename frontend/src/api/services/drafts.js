import apiClient from '../apiClient';

export const draftService = {
  getAll: async () => (await apiClient.get('/drafts')).data,
  getById: async (id) => (await apiClient.get(`/drafts/${id}`)).data,
  create: async (data) => (await apiClient.post('/drafts', data)).data,
  update: async (id, data) => (await apiClient.put(`/drafts/${id}`, data)).data,
  delete: async (id) => (await apiClient.delete(`/drafts/${id}`)).data,
};