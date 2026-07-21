import apiClient from './apiClient';

export const authService = {
  login: async (username, password) => {
    const response = await apiClient.post('/auth/login', { username, password });
    const token = response?.data?.data?.token || response?.data?.data?.accessToken;
    if (response.data.success && token) {
      localStorage.setItem('token', token);
      // Normalize backend user shape: backend returns `roleName` while frontend expects `roles` array
      const backendUser = response.data.data.user || {};
      const normalizedUser = {
        ...backendUser,
        roles: backendUser.roles || (backendUser.roleName ? [{ name: backendUser.roleName.replace(/^ROLE_/, '') }] : []),
      };
      localStorage.setItem('user', JSON.stringify(normalizedUser));
      // also update response payload so callers receive normalized user and token
      response.data.data.user = normalizedUser;
      response.data.data.token = token;
      response.data.data.accessToken = token;
    }
    return response.data;
  },

  logout: () => {
    localStorage.removeItem('token');
    localStorage.removeItem('user');
  },

  getCurrentUser: () => {
    const userStr = localStorage.getItem('user');
    if (!userStr) return null;
    const user = JSON.parse(userStr);
    // Ensure normalized roles shape for older stored users
    if (!user.roles && user.roleName) {
      user.roles = [{ name: user.roleName.replace(/^ROLE_/, '') }];
    }
    return user;
  },

  getToken: () => {
    return localStorage.getItem('token');
  },
};

export const productService = {
  getAll: async (page = 0, size = 20, sortBy = 'createdAt', categoryId = null) => {
    const params = new URLSearchParams({ page: String(page), size: String(size), sortBy });
    if (categoryId) params.append('categoryId', categoryId);
    const response = await apiClient.get(`/products?${params.toString()}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/products/${id}`);
    return response.data;
  },

  create: async (formData) => {
    const response = await apiClient.post('/products', formData);
    return response.data;
  },

  update: async (id, formData) => {
    const response = await apiClient.put(`/products/${id}`, formData);
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/products/${id}`);
    return response.data;
  },

  getImage: async (productId) => {
    const response = await apiClient.get(`/products/${productId}/image`, {
      responseType: 'blob',
    });
    return response.data;
  },

  uploadImage: async (productId, file) => {
    const formData = new FormData();
    formData.append('file', file);
    const response = await apiClient.post(`/products/${productId}/image`, formData,
          {
            headers: {
              'Content-Type': 'multipart/form-data',
            },
          });
    return response.data;
  },

  deleteImage: async (productId) => {
    const response = await apiClient.delete(`/products/${productId}/image`);
    return response.data;
  },

  getLowStock: async (threshold = 10) => {
    const response = await apiClient.get(`/inventory/products/low-stock?threshold=${threshold}`);
    return response.data;
  },

  importProducts: async (file) => {
    const formData = new FormData();
    formData.append('file', file);
    const response = await apiClient.post('/products/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return response.data;
  },

  downloadExport: async () => {
    const response = await apiClient.get('/products/export', { responseType: 'blob' });
    const url = window.URL.createObjectURL(new Blob([response.data]));
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', 'products.csv');
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
  },
};

export const categoryService = {
  getAll: async (page = 0, size = 20) => {
    const response = await apiClient.get(`/categories?page=${page}&size=${size}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/categories/${id}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/categories', data);
    return response.data;
  },

  update: async (id, data) => {
    const response = await apiClient.put(`/categories/${id}`, data);
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/categories/${id}`);
    return response.data;
  },
};

export const supplierService = {
  getAll: async (page = 0, size = 20) => {
    const response = await apiClient.get(`/suppliers?page=${page}&size=${size}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/suppliers/${id}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/suppliers', data);
    return response.data;
  },

  update: async (id, data) => {
    const response = await apiClient.put(`/suppliers/${id}`, data);
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/suppliers/${id}`);
    return response.data;
  },

  search: async (query) => {
    const response = await apiClient.get(`/suppliers/search?query=${encodeURIComponent(query)}`);
    return response.data;
  },
};

export const purchaseService = {
  getAll: async (page = 0, size = 20, sortBy = 'purchaseDate') => {
    const response = await apiClient.get(`/purchases?page=${page}&size=${size}&sortBy=${sortBy}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/purchases/${id}`);
    return response.data;
  },

  getByNumber: async (purchaseNumber) => {
    const response = await apiClient.get(`/purchases/number/${purchaseNumber}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/purchases', data);
    return response.data;
  },

  updatePaymentStatus: async (id, paymentStatus) => {
    const response = await apiClient.patch(`/purchases/${id}/payment-status`, { paymentStatus });
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/purchases/${id}`);
    return response.data;
  },
};

export const customerService = {
  getAll: async (page = 0, size = 20) => {
    const response = await apiClient.get(`/customers?page=${page}&size=${size}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/customers/${id}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/customers', data);
    return response.data;
  },

  update: async (id, data) => {
    const response = await apiClient.put(`/customers/${id}`, data);
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/customers/${id}`);
    return response.data;
  },

  search: async (keyword, page = 0, size = 20) => {
      const response = await apiClient.get(
          `/customers/search?keyword=${encodeURIComponent(keyword)}&page=${page}&size=${size}`
      );
      return response.data;
  },

  addCreditPayment: async (customerId, data) => {
    const response = await apiClient.post(`/customers/${customerId}/credit-payments`, data);
    return response.data;
  },

  downloadExport: async () => {
    const response = await apiClient.get('/customers/export', { responseType: 'blob' });
    const url = window.URL.createObjectURL(new Blob([response.data]));
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', 'customers.csv');
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
  },
};

export const saleService = {
  getAll: async (page = 0, size = 20, sortBy = 'saleDate') => {
    const response = await apiClient.get(`/sales?page=${page}&size=${size}&sortBy=${sortBy}`);
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

  refundSale: async (id, data) => {
    const response = await apiClient.post(`/sales/${id}/refund`, data);
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/sales/${id}`);
    return response.data;
  },

  getByDateRange: async (startDate, endDate) => {
    const response = await apiClient.get(`/sales/date-range?startDate=${startDate}&endDate=${endDate}`);
    return response.data;
  },

verifyCart: async (cart) => {
    const response = await apiClient.post('/sales/verify-cart', {
      items: cart.map((item) => ({
        productId: item.productId,
        quantity: item.quantity,
        expectedUnitPrice: item.price,
      })),
    });
    return response.data;
  },

  downloadExport: async (startDate, endDate) => {
    const params = new URLSearchParams();
    if (startDate) params.append('startDate', startDate);
    if (endDate) params.append('endDate', endDate);
    const response = await apiClient.get(`/sales/export?${params.toString()}`, { responseType: 'blob' });
    const url = window.URL.createObjectURL(new Blob([response.data]));
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', 'sales.csv');
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
  },
};

export const receiptService = {
  getByInvoiceNumber: async (invoiceNumber) => {
    const response = await apiClient.get(`/receipts/invoice/${invoiceNumber}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/receipts/${id}`);
    return response.data;
  },

  getPrintHtml: async (invoiceNumber) => {
    const response = await apiClient.get(`/receipts/invoice/${invoiceNumber}/print`, {
      headers: { 'Accept': 'text/html' },
    });
    return response.data;
  },

  downloadReceipt: async (invoiceNumber, format) => {
    const response = await apiClient.get(`/receipts/invoice/${invoiceNumber}/${format}`, {
      responseType: 'blob',
    });
    return response.data;
  },
};

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

  getReorderSuggestions: async () => {
    const response = await apiClient.get('/inventory/reorder-suggestions');
    return response.data;
  },

  getStockMovements: async (productId, page = 0, size = 20) => {
    const response = await apiClient.get(`/inventory/product/${productId}/movements?page=${page}&size=${size}`);
    return response.data;
  },
};

export const shopInfoService = {
  get: async () => {
    const response = await apiClient.get('/shop-info');
    return response.data;
  },

  update: async (data) => {
    const response = await apiClient.put('/shop-info', data);
    return response.data;
  },

  getLogo: async () => {
    const response = await apiClient.get('/shop-info/logo', { responseType: 'blob' });
    return response.data;
  },

  uploadLogo: async (file) => {
    const formData = new FormData();
    formData.append('file', file);
    const response = await apiClient.post('/shop-info/logo', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return response.data;
  },

  deleteLogo: async () => {
    const response = await apiClient.delete('/shop-info/logo');
    return response.data;
  },
};

export const expenseService = {
  getAll: async (month = null, year = null) => {
    const params = new URLSearchParams();
    if (month) params.append('startDate', `${year || new Date().getFullYear()}-${String(month).padStart(2, '0')}-01`);
    if (month) params.append('endDate', `${year || new Date().getFullYear()}-${String(month).padStart(2, '0')}-${new Date(year || new Date().getFullYear(), month, 0).getDate()}`);
    const response = await apiClient.get(`/expenses${params.toString() ? `?${params.toString()}` : ''}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/expenses', data);
    return response.data;
  },

  update: async (id, data) => {
    const response = await apiClient.put(`/expenses/${id}`, data);
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/expenses/${id}`);
    return response.data;
  },

  getReceiptImage: async (id) => {
    const response = await apiClient.get(`/expenses/${id}/receipt-image`, {
      responseType: 'blob',
    });
    return response.data;
  },

  uploadReceiptImage: async (id, file) => {
    const formData = new FormData();
    formData.append('file', file);
    const response = await apiClient.post(`/expenses/${id}/receipt-image`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return response.data;
  },

  deleteReceiptImage: async (id) => {
    const response = await apiClient.delete(`/expenses/${id}/receipt-image`);
    return response.data;
  },
};

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

  getTopProducts: async (period = 'MONTH', limit = 10) => {
    const response = await apiClient.get(`/reports/top-products?period=${period}&limit=${limit}`);
    return response.data;
  },

  getTopCategories: async (period = 'MONTH') => {
    const response = await apiClient.get(`/reports/top-categories?period=${period}`);
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

  getAccountingSummary: async (year, month) => {
    const response = await apiClient.get(`/reports/accounting-summary?year=${year}&month=${month}`);
    return response.data;
  },
};

export const userService = {
  getAll: async (page = 0, size = 20) => {
    const response = await apiClient.get(`/users?page=${page}&size=${size}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/users/${id}`);
    return response.data;
  },

  create: async (data) => {
    const response = await apiClient.post('/users', data);
    return response.data;
  },

  update: async (id, data) => {
    const response = await apiClient.put(`/users/${id}`, data);
    return response.data;
  },

  delete: async (id) => {
    const response = await apiClient.delete(`/users/${id}`);
    return response.data;
  },
};

export const systemSettingService = {
  getAll: async () => {
    const response = await apiClient.get('/settings');
    return response.data;
  },

  getByKey: async (key) => {
    const response = await apiClient.get(`/settings/${key}`);
    return response.data;
  },

  update: async (key, data) => {
    const response = await apiClient.put(`/settings/${key}`, data);
    return response.data;
  },
};

export const auditLogService = {
  getAll: async (page = 0, size = 20, filters = {}) => {
    const params = new URLSearchParams({ page, size });
    if (filters.userId) params.append('userId', filters.userId);
    if (filters.action) params.append('action', filters.action);
    if (filters.startDate) params.append('startDate', filters.startDate);
    if (filters.endDate) params.append('endDate', filters.endDate);
    
    const response = await apiClient.get(`/audit-logs?${params.toString()}`);
    return response.data;
  },
};

export const cashShiftService = {
  open: async (data) => {
    const response = await apiClient.post("/shifts/open", data);
    return response.data;
  },

  getCurrent: async () => {
    const response = await apiClient.get("/shifts/current");
    return response.data;
  },

  close: async (id, data) => {
    const response = await apiClient.post(`/shifts/${id}/close`, data);
    return response.data;
  },

  getHistory: async (params = {}) => {
    const queryParams = new URLSearchParams();
    if (params.startDate) queryParams.append("startDate", params.startDate);
    if (params.endDate) queryParams.append("endDate", params.endDate);
    if (params.cashierId) queryParams.append("cashierId", params.cashierId);
    const response = await apiClient.get(`/shifts?${queryParams.toString()}`);
    return response.data;
  },

  getById: async (id) => {
    const response = await apiClient.get(`/shifts/${id}`);
    return response.data;
  },
};
