import apiClient from '../apiClient';

// Auto-split from services.js — domain: audit

export const auditLogService = {
  getAll: async (page = 0, size = 20, filters = {}) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (filters.userId) params.append('userId', filters.userId);
    if (filters.action) params.append('action', filters.action);
    if (filters.startDate) params.append('startDate', filters.startDate);
    if (filters.endDate) params.append('endDate', filters.endDate);
    
    const response = await apiClient.get(`/audit-logs?${params.toString()}`);
    return response.data;
  },
};
