import apiClient from '../apiClient';

// Auto-split from services.js — domain: inventory

export const inventoryService = {
  adjustStock: async (productId, adjustment) => {
    const response = await apiClient.post(
      `/inventory/products/${productId}/adjust`,
      {
        productId: adjustment.productId,
        quantityChange: adjustment.quantityChange,
        reason: adjustment.reason,
      }
    );
    return response.data;
  },

  getLowStock: async (threshold = 10) => {
    const response = await apiClient.get(`/inventory/products/low-stock?threshold=${threshold}`);
    return response.data;
  },

  getProducts: async ({ page = 0, size = 1000, categoryId = null } = {}) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (categoryId) params.append('categoryId', String(categoryId));
    const response = await apiClient.get(`/inventory/products?${params.toString()}`);
    return response.data;
  },

  // Headline counts + valuation + category breakdown + low-stock watchlist
  getSummary: async () => {
    const response = await apiClient.get('/inventory/summary');
    return response.data;
  },

  // Global movement ledger. All filters optional; server-paged, newest first.
  getMovements: async ({
    page = 0, size = 20, productId = null, type = '',
    search = '', dateFrom = null, dateTo = null,
  } = {}) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (productId) params.append('productId', String(productId));
    if (type) params.append('type', type);
    if (search) params.append('search', search);
    if (dateFrom) params.append('dateFrom', dateFrom);
    if (dateTo) params.append('dateTo', dateTo);
    const response = await apiClient.get(`/inventory/movements?${params.toString()}`);
    return response.data;
  },

  // Daily IN vs OUT totals + cause mix over the last N days
  getMovementStats: async (days = 30) => {
    const response = await apiClient.get(`/inventory/movement-stats?days=${days}`);
    return response.data;
  },
};
