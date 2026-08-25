import apiClient from '../apiClient';

// Auto-split from services.js — domain: license

export const licenseService = {
    getMachineId: () => apiClient.get('/license/machine-id'),
    getStatus: () => apiClient.get('/license/status'),
    activate: (licenseKey) => apiClient.post('/license/activate', { licenseKey }),
};
