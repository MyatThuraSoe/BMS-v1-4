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

  runNow: async (startDate = null, endDate = null, passphrase = null) => {
    const params = new URLSearchParams();
    if (startDate) params.append('startDate', startDate);
    if (endDate) params.append('endDate', endDate);

    // The passphrase travels in the body, never the query string: a URL ends up in
    // server access logs and browser history, and this one opens the shop's backups.
    const response = await apiClient.post(
      `/backups/run-now?${params.toString()}`,
      passphrase ? { passphrase } : {}
    );
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

  /**
   * Restores a JSON backup. Returns a job id to poll.
   * `passphrase` is only needed for a file whose name ends in .enc.
   */
  restoreFromDrive: async (fileId, sizeBytes, mode, passphrase = null, confirmSameShop = false) => {
    const response = await apiClient.post('/backups/drive/restore',
      { fileId, sizeBytes, mode, passphrase: passphrase || undefined,
        confirmSameShop: confirmSameShop || undefined });
    return response.data.data.jobId;
  },

  /**
   * Downloads a raw database snapshot and stages it for the next start.
   * The app must be restarted afterwards.
   */
  restoreDatabaseFromDrive: async (fileId, sizeBytes, passphrase = null) => {
    const response = await apiClient.post('/backups/drive/restore-database',
      { fileId, sizeBytes, passphrase: passphrase || undefined });
    return response.data.data.jobId;
  },

  /** `passphrase` seals the snapshot before it is uploaded, when one was typed. */
  createDatabaseSnapshot: async (passphrase = null) => {
    const response = await apiClient.post('/backups/drive/snapshot',
      passphrase ? { passphrase } : {});
    return response.data.data.jobId;
  },

  getJob: async (jobId) => {
    const response = await apiClient.get(`/backups/jobs/${jobId}`);
    return response.data.data;
  },

  /** { pending, damagedDatabase }: a restart still owed, and damage already replaced. */
  getRestoreStatus: async () => {
    const response = await apiClient.get('/backups/restore-pending');
    return response.data.data;
  },

  dismissDamagedDatabase: async () => {
    await apiClient.delete('/backups/restore-pending/damaged-database');
  },

  /**
   * Deletes one backup from the Drive folder. Manual copies are kept until the
   * shop deletes them, so this is how their space comes back. The server only
   * allows a file of this app's own naming inside the backup folder.
   */
  deleteDriveFile: async (fileId) => {
    const response = await apiClient.delete(`/backups/drive/files/${fileId}`);
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
    /**
     * @param confirmSameShop the user's own answer to "is this file ours?". Only
     *                        a tick may send true; the server still checks the
     *                        rows before it merges anything.
     */
    importAll: (backupJson, mode, confirmSameShop = false) => apiClient.post(
        `/data/import?mode=${mode}&confirmSameShop=${confirmSameShop}`,
        backupJson,
    ),
    /**
     * The id of the install standing here, so the import page can tell a file
     * made by it from a file made somewhere else before merging anything.
     */
    installId: async () => (await apiClient.get('/data/install-id')).data.data.installId || '',
};
