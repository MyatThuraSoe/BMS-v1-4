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

export const backupRestoreService = {
  /** Backups sitting in the connected Google Drive folder, newest first. */
  listDriveFiles: async () => {
    const response = await apiClient.get('/backups/drive/files');
    return response.data.data;
  },

  /** Restores a JSON backup. Returns a job id to poll. */
  restoreFromDrive: async (fileId, sizeBytes, mode) => {
    const response = await apiClient.post('/backups/drive/restore', { fileId, sizeBytes, mode });
    return response.data.data.jobId;
  },

  /**
   * Downloads a raw database snapshot and stages it for the next start.
   * The app must be restarted afterwards.
   */
  restoreDatabaseFromDrive: async (fileId, sizeBytes) => {
    const response = await apiClient.post('/backups/drive/restore-database', { fileId, sizeBytes });
    return response.data.data.jobId;
  },

  createDatabaseSnapshot: async () => {
    const response = await apiClient.post('/backups/drive/snapshot');
    return response.data.data.jobId;
  },

  getJob: async (jobId) => {
    const response = await apiClient.get(`/backups/jobs/${jobId}`);
    return response.data.data;
  },

  isRestorePending: async () => {
    const response = await apiClient.get('/backups/restore-pending');
    return response.data.data.pending;
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
