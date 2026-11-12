import apiClient from '../apiClient';

// Auto-split from services.js — domain: shifts
//
// There are two levels here:
//   - employee shifts (cash drawer / non-cash / supervisor)
//   - physical drawer sessions shared by one or more employees
// The backend allows cash only when both levels are open and attached.

export const shiftService = {
  openShift: async (options = {}) => {
    // Legacy callers passed just the opening amount.
    const payload = typeof options === 'number'
      ? { shiftType: 'CASH_DRAWER', openingAmount: options }
      : {
          shiftType: options.shiftType || 'CASH_DRAWER',
          drawerId: options.drawerId ?? null,
          openingAmount: options.openingAmount ?? null,
          openingFloat: options.openingFloat ?? null,
          joinExisting: options.joinExisting ?? null,
          approverUsername: options.approverUsername ?? null,
          approverPassword: options.approverPassword ?? null,
          notes: options.notes ?? null,
        };
    const response = await apiClient.post('/shifts/open', payload);
    return response.data;
  },

  getCurrentShift: async () => {
    const response = await apiClient.get('/shifts/current');
    return response.data;
  },

  getStartContext: async () => {
    const response = await apiClient.get('/shifts/start-context');
    return response.data;
  },

  getActiveShifts: async () => {
    const response = await apiClient.get('/shifts/active');
    return response.data;
  },

  closeShift: async (id, options = {}, notes = '') => {
    // Legacy callers passed the counted amount as the second argument.
    const payload = typeof options === 'number' || typeof options === 'string'
      ? { closingAmount: options, notes }
      : {
          countedCash: options.countedCash ?? null,
          closingNote: options.closingNote ?? options.notes ?? null,
          closeDrawer: options.closeDrawer ?? null,
          forceClose: options.forceClose ?? false,
          forceReason: options.forceReason ?? null,
        };
    const response = await apiClient.post(`/shifts/${id}/close`, payload);
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

  // ---- Physical drawer sessions -----------------------------------------
  getActiveDrawerSessions: async () => {
    const response = await apiClient.get('/drawer-sessions/active');
    return response.data;
  },

  getDrawerSessionHistory: async (params = {}) => {
    const query = new URLSearchParams();
    if (params.startDate) query.append('startDate', params.startDate);
    if (params.endDate) query.append('endDate', params.endDate);
    if (params.page != null) query.append('page', String(params.page));
    if (params.size) query.append('size', String(params.size));
    const response = await apiClient.get(`/drawer-sessions?${query.toString()}`);
    return response.data;
  },

  getDrawerSession: async (id) => {
    const response = await apiClient.get(`/drawer-sessions/${id}`);
    return response.data;
  },

  getDrawerMovements: async (id) => {
    const response = await apiClient.get(`/drawer-sessions/${id}/movements`);
    return response.data;
  },

  closeDrawerSession: async (id, payload) => {
    const response = await apiClient.post(`/drawer-sessions/${id}/close`, {
      countedCash: payload.countedCash,
      closingNote: payload.closingNote ?? payload.notes ?? null,
      forceClose: payload.forceClose ?? false,
      forceReason: payload.forceReason ?? null,
    });
    return response.data;
  },

  performDrawerMovement: async (id, payload) => {
    const response = await apiClient.post(`/drawer-sessions/${id}/movements`, {
      movementType: payload.movementType,
      amount: payload.amount,
      direction: payload.direction ?? null,
      reason: payload.reason ?? null,
      externalReference: payload.externalReference ?? null,
    });
    return response.data;
  },
};
