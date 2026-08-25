import apiClient from '../apiClient';

// Auto-split from services.js — domain: users

export const userService = {
  getAll: async (page = 0, size = 20) => {
    const response = await apiClient.get(`/users?page=${page}&size=${size}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/users/${id}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/users', data);
    return response.data;
  },

  update: async (id, data) => {
    const response = await apiClient.put(`/users/${id}`, data);
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/users/${id}`);
    return response.data;
  },

  updateLanguage: async (lang) => {
    const response = await apiClient.patch(`/users/me/language?lang=${lang}`);
    return response.data;
  },

  getStats: async (id) => {
    const response = await apiClient.get(`/users/${id}/stats`);
    return response.data;
  },
};
