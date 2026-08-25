import apiClient from '../apiClient';

// Auto-split from services.js — domain: sales

export const saleService = {
  getAll: async (page = 0, size = 20, sortBy = 'saleDate', range = null, startDate = null, endDate = null, customerId = null, invoice = null) => {
    const params = new URLSearchParams({ page: String(page), size: String(size), sortBy });
    if (range) params.append('range', range);
    if (startDate) params.append('startDate', startDate);
    if (endDate) params.append('endDate', endDate);
    if (customerId) params.append('customerId', customerId);
    if (invoice) params.append('invoice', invoice);
    const response = await apiClient.get(`/sales?${params.toString()}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/sales/${id}`);
    return response.data;
  },

  getByInvoiceNumber: async (invoiceNumber) => {
    const response = await apiClient.get(`/sales/invoice/${invoiceNumber}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/sales', data);
    return response.data;
  },

  voidSale: async (id, reason) => {
    const response = await apiClient.post(`/sales/${id}/void?reason=${encodeURIComponent(reason)}`);
    return response.data;
  },

  createSaleReturn: async (id, data) => {
    const response = await apiClient.post(`/sales/${id}/returns`, data);
    return response.data;
  },

  getReturnableItems: async (id) => {
    const response = await apiClient.get(`/sales/${id}/returnable-items`);
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/sales/${id}`);
    return response.data;
  },

  deleteOld: async (olderThanYears = 1) => {
    const response = await apiClient.delete(`/sales/old?olderThanYears=${olderThanYears}`);
    return response.data;
  },

  getByDateRange: async (startDate, endDate) => {
    const response = await apiClient.get(`/sales/date-range?startDate=${startDate}&endDate=${endDate}`);
    return response.data;
  },

  verifyCart: async (cart, discountAmount = null) => {
    const response = await apiClient.post('/sales/verify-cart', {
      items: cart.map((item) => ({
        productId: item.productId,
        quantity: item.quantity,
        expectedUnitPrice: item.price,
      })),
      discountAmount: discountAmount != null && discountAmount !== '' ? Number(discountAmount) : null,
    });
    return response.data;
  },

  getCustomerStats: async (customerId) => {
    const response = await apiClient.get(`/sales/customer/${customerId}/stats`);
    return response.data;
  },

  getCustomerTopProducts: async (customerId) => {
    const response = await apiClient.get(`/sales/customer/${customerId}/top-products`);
    return response.data;
  },

  getCustomerDailySpending: async (customerId, year) => {
    const response = await apiClient.get(`/sales/customer/${customerId}/daily-spending/${year}`);
    return response.data;
  },
};

export const saleReturnService = {
  getAll: async (page = 0, size = 20, saleId = null, invoice = null) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (saleId) params.append('saleId', saleId);
    if (invoice) params.append('invoice', invoice);
    const response = await apiClient.get(`/sale-returns?${params.toString()}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/sale-returns/${id}`);
    return response.data;
  },
};
