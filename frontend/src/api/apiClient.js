// frontend/src/api/apiClient.jsx

import axios from 'axios';

const API_BASE_URL = '/api';

const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor to add JWT token
apiClient.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// Response interceptor to handle errors
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status;
    const backendMessage = error.response?.data?.message;

    // 1. Handle JWT Expiration / Unauthorized Access
    if (status === 401 || status === 403) {
      // Prevent infinite redirect loop if already on the login page
      if (!window.location.pathname.includes('/login')) {
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        window.location.href = '/login';
      }
      return Promise.reject(error);
    }

    // 2. Handle other known errors with friendly messages
    let friendlyMessage;
    if (!error.response) {
      friendlyMessage = 'Cannot reach the server. Check your connection and try again.';
    } else if (status === 409) {
      friendlyMessage = backendMessage || 'That action conflicts with existing data (e.g., a duplicate).';
    } else if (status === 400) {
      friendlyMessage = backendMessage || 'Please check the form for errors.';
    } else if (status >= 500) {
      friendlyMessage = 'Something went wrong on our end. Please try again in a moment.';
    } else {
      friendlyMessage = backendMessage || 'Something went wrong.';
    }

    error.friendlyMessage = friendlyMessage;
    return Promise.reject(error);
  }
);

export default apiClient;
