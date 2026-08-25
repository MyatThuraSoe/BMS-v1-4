import apiClient from '../apiClient';

// Auto-split from services.js — domain: backup

export const backupService = {
  downloadFullBackup: async () => {
    const response = await apiClient.get('/backups/export', { responseType: 'blob' });
    return response.data;
  },

  getSettings: async () => {
    const response = await apiClient.get('/backups/settings');
    return response.data;
  },

  updateSettings: async (settings) => {
    const response = await apiClient.put('/backups/settings', settings);
    return response.data;
  },

  runNow: async (startDate = null, endDate = null) => {
    const params = new URLSearchParams();
    if (startDate) params.append('startDate', startDate);
    if (endDate) params.append('endDate', endDate);
    
    const response = await apiClient.post(`/backups/run-now?${params.toString()}`);
    return response.data;
  },

  getConnectUrl: async () => {
    // Returns the Google OAuth URL as plain text
    const response = await apiClient.get('/backups/google/connect', {
      responseType: 'text' 
    });
    return response.data;
  },

  disconnect: async () => {
    const response = await apiClient.post('/backups/google/disconnect');
    return response.data;
  }
};

export const googleDriveService = {
  getAuthUrl: () => apiClient.get('/backups/google/auth-url'),
  getStatus: () => apiClient.get('/backups/google/status'),
  disconnect: () => apiClient.post('/backups/google/disconnect'),
};

export const dataService = {
    exportAll: () => apiClient.get('/data/export', { responseType: 'blob' }),
    importAll: (backupJson, mode) => apiClient.post(`/data/import?mode=${mode}`, backupJson),
};
