import apiClient from '../apiClient';

// Auto-split from services.js — domain: auth

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

  changePassword: async (currentPassword, newPassword) => {
    const response = await apiClient.post('/auth/change-password', {
      currentPassword,
      newPassword,
    });
    return response.data;
  },

  registerFirstAdmin: async (payload) => {
    const response = await apiClient.post('/auth/register-first-admin', payload);
    return response.data;
  },
};
