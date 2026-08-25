import apiClient from '../apiClient';

// Auto-split from services.js — domain: catalog

export const productService = {
  getAll: async (page = 0, size = 20, sortBy = 'createdAt', categoryId = null, view = null) => {
    const params = new URLSearchParams({ page: String(page), size: String(size), sortBy });
    if (categoryId) params.append('categoryId', categoryId);
    if (view) params.append('view', view);
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

  getSuppliers: async (productId) => {
    const response = await apiClient.get(`/products/${productId}/suppliers`);
    return response.data;
  },

  search: async (keyword, page = 0, size = 20) => {
    const response = await apiClient.get(`/products/search?keyword=${encodeURIComponent(keyword)}&page=${page}&size=${size}`);
    return response.data;
  },

  getCostHistory: async (productId) => {
    const response = await apiClient.get(`/products/${productId}/cost-history`);
    return response.data;
  },

  getPriceHistory: async (productId) => {
    const response = await apiClient.get(`/products/${productId}/price-history`);
    return response.data;
  },

  getTopCustomers: async (productId, limit = 10) => {
    const response = await apiClient.get(`/products/${productId}/top-customers?limit=${limit}`);
    return response.data;
  },

  getSalesSummary: async (productId) => {
    const response = await apiClient.get(`/products/${productId}/sales-summary`);
    return response.data;
  },
};

export const categoryService = {
  getAll: async (page = 0, size = 20) => {
    const response = await apiClient.get(`/categories?page=${page}&size=${size}`);
    return response.data;
  },

  getStatsSummary: async () => {
    const response = await apiClient.get('/categories/stats/summary');
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
