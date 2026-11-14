import apiClient from '../apiClient';

// P1/O8: ledger cutover, backfills, reconciliation and period control (ADMIN).

export const accountingService = {
  getPeriods: async () => {
    const response = await apiClient.get('/accounting/periods');
    return response.data;
  },

  closePeriod: async (period) => {
    const response = await apiClient.post(`/accounting/periods/${period}/close`);
    return response.data;
  },

  reopenPeriod: async (period, reason) => {
    const response = await apiClient.post(`/accounting/periods/${period}/reopen`, { reason });
    return response.data;
  },

  getReconciliation: async () => {
    const response = await apiClient.get('/accounting/reconciliation');
    return response.data;
  },

  getCostReconciliation: async () => {
    const response = await apiClient.get('/accounting/cost-reconciliation');
    return response.data;
  },

  backfillProfit: async (backupConfirmed) => {
    const response = await apiClient.post('/accounting/profit-backfill', { backupConfirmed });
    return response.data;
  },

  backfillInventory: async (backupConfirmed) => {
    const response = await apiClient.post('/accounting/inventory-backfill', { backupConfirmed });
    return response.data;
  },

  activateLedgerReads: async () => {
    const response = await apiClient.post('/accounting/ledger-cutover');
    return response.data;
  },
};