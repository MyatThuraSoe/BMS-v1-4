import apiClient from '../apiClient';

// Auto-split from services.js — domain: shifts

export const shiftService = {
  openShift: async (openingAmount) => {
    const response = await apiClient.post('/shifts/open', { openingAmount });
    return response.data;
  },

  getCurrentShift: async () => {
    const response = await apiClient.get('/shifts/current');
    return response.data;
  },

  closeShift: async (id, closingAmount, notes = '') => {
    const response = await apiClient.post(`/shifts/${id}/close`, { closingAmount, notes });
    return response.data;
  },

  getShiftHistory: async (params = {}) => {
    const query = new URLSearchParams();
    if (params.cashierId) query.append('cashierId', params.cashierId);
    if (params.startDate) query.append('startDate', params.startDate);
    if (params.endDate) query.append('endDate', params.endDate);
    if (params.page != null) query.append('page', String(params.page));
    if (params.size) query.append('size', String(params.size));
    const response = await apiClient.get(`/shifts?${query.toString()}`);
    return response.data;
  },

  getShiftById: async (id) => {
    const response = await apiClient.get(`/shifts/${id}`);
    return response.data;
  },
};
