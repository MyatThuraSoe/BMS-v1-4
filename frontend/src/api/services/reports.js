import apiClient from '../apiClient';

// Auto-split from services.js — domain: reports

export const reportService = {
  getDailySales: async (date) => {
    const response = await apiClient.get(`/reports/daily-sales?date=${date}`);
    return response.data;
  },

  getMonthlySales: async (year, month) => {
    const response = await apiClient.get(`/reports/monthly-sales?year=${year}&month=${month}`);
    return response.data;
  },

  getProductPerformance: async (startDate, endDate) => {
    const response = await apiClient.get(`/reports/product-performance?startDate=${startDate}&endDate=${endDate}`);
    return response.data;
  },

  getTopSellingProducts: async (limit = 10, startDate, endDate) => {
    const response = await apiClient.get(`/reports/top-selling-products?limit=${limit}&startDate=${startDate}&endDate=${endDate}`);
    return response.data;
  },

  getCashierPerformance: async (startDate, endDate) => {
    const response = await apiClient.get(`/reports/cashier-performance?startDate=${startDate}&endDate=${endDate}`);
    return response.data;
  },

  getSalesTrend: async (days = 7) => {
    const response = await apiClient.get(`/reports/sales-trend?days=${days}`);
    return response.data;
  },

  getInventoryReport: async () => {
    const response = await apiClient.get('/reports/inventory');
    return response.data;
  },

  // --- UPDATED: Added compareMode parameter ---
  getTopProducts: async (period = 'MONTH', limit = 10, compareMode = 'PREVIOUS_PERIOD') => {
    const params = new URLSearchParams({ period, limit: String(limit), compareMode });
    const response = await apiClient.get(`/reports/top-products?${params.toString()}`);
    return response.data;
  },

  // --- UPDATED: Added compareMode parameter ---
  getTopCategories: async (period = 'MONTH', compareMode = 'PREVIOUS_PERIOD') => {
    const params = new URLSearchParams({ period, compareMode });
    const response = await apiClient.get(`/reports/top-categories?${params.toString()}`);
    return response.data;
  },

  // --- NEW: Added compareCategories method ---
  compareCategories: async (categoryIds, period = 'MONTH') => {
    const params = new URLSearchParams({ period });
    categoryIds.forEach((id) => params.append('categoryIds', String(id)));
    const response = await apiClient.get(`/reports/compare-categories?${params.toString()}`);
    return response.data;
  },

  getProfitSummary: async (startDate, endDate) => {
    const response = await apiClient.get(`/reports/profit-summary?startDate=${startDate}&endDate=${endDate}`);
    return response.data;
  },

  getProfitTrend: async (period = 'MONTH') => {
    const response = await apiClient.get(`/reports/profit-trend?period=${period}&points=12`);
    return response.data;
  },

  getProfitBySupplier: async (startDate, endDate) => {
    const response = await apiClient.get(`/reports/profit-by-supplier?startDate=${startDate}&endDate=${endDate}`);
    return response.data;
  },

  getAccountingSummary: async (year, month) => {
    const response = await apiClient.get(`/reports/accounting-summary?year=${year}&month=${month}`);
    return response.data;
  },

  getFinancialSummary: async (startDate, endDate) => {
    const response = await apiClient.get(`/reports/financial-summary?startDate=${startDate}&endDate=${endDate}`);
    return response.data;
  },

  // §1 Dead Stock
  getDeadStock: async (daysThreshold = 30) => {
    const response = await apiClient.get(`/reports/dead-stock?daysThreshold=${daysThreshold}`);
    return response.data;
  },

  // §2 Sales Timing Heatmap
  getSalesTiming: async (startDate, endDate) => {
    const response = await apiClient.get(`/reports/sales-timing?startDate=${startDate}&endDate=${endDate}`);
    return response.data;
  },

  // §3 Customer Retention & LTV
  getCustomerLifetimeValue: async () => {
    const response = await apiClient.get('/reports/customer-lifetime-value');
    return response.data;
  },

  getCustomerRetention: async () => {
    const response = await apiClient.get('/reports/customer-retention');
    return response.data;
  },

  // §4 Basket Analysis
  getFrequentlyBoughtWith: async (productId, limit = 5) => {
    const response = await apiClient.get(`/reports/products/${productId}/frequently-bought-with?limit=${limit}`);
    return response.data;
  },
};
