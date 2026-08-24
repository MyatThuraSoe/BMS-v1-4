// frontend/src/api/apiClient.jsx

import axios from 'axios';
import i18n from '../i18n';

const API_BASE_URL = '/api';

const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});
// Request interceptor to add JWT token + Accept-Language header
apiClient.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    // Tell the backend which language to use for server-side messages
    const lang = i18n.language?.split('-')[0] || localStorage.getItem('bms_language') || 'en';
    config.headers['Accept-Language'] = lang;
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
    const code = error.response?.data?.code;

    // 1. License required: hard redirect to activation, never to login
    if (status === 403 && code === 'LICENSE_REQUIRED') {
      if (!window.location.pathname.startsWith('/activate')) {
        window.location.href = '/activate';
      }
      error.friendlyMessage = backendMessage || i18n.t('errors:license_required');
      return Promise.reject(error);
    }

    // 2. Handle JWT Expiration / Unauthorized Access
    if (status === 401) {
      // Prevent infinite redirect loop if already on the login page
      if (!window.location.pathname.includes('/login')) {
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        window.location.href = '/login';
      }
      return Promise.reject(error);
    }

    // 2b. Role/privilege denied — the session is still valid, the caller just
    // lacks the required role. Don't log the user out; surface the message.
    if (status === 403) {
      error.friendlyMessage = backendMessage || i18n.t('errors:forbidden');
      return Promise.reject(error);
    }

    // 3. Handle other known errors with friendly messages
    let friendlyMessage;
    if (!error.response) {
      friendlyMessage = i18n.t('errors:cannot_reach_server');
    } else if (status === 409) {
      friendlyMessage = backendMessage || i18n.t('errors:conflict');
    } else if (status === 400) {
      friendlyMessage = backendMessage || i18n.t('errors:check_form');
    } else if (status >= 500) {
      friendlyMessage = i18n.t('errors:unexpected_error');
    } else {
      friendlyMessage = backendMessage || i18n.t('errors:generic');
    }

    error.friendlyMessage = friendlyMessage;
    return Promise.reject(error);
  }
);

export default apiClient;
