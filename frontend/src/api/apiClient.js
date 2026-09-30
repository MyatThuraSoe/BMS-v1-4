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

// The 'errors' namespace loads on demand, so the first t() call for it would
// return the raw key. Wait for it once and always translate after it is loaded.
let errorsNamespaceLoading = null;
const translateWithErrorsLoaded = async (key, backendMessage) => {
  if (backendMessage) return backendMessage;
  if (!errorsNamespaceLoading) {
    errorsNamespaceLoading = i18n.loadNamespaces('errors').catch(() => {});
  }
  await errorsNamespaceLoading;
  const value = i18n.t(key);
  errorsNamespaceLoading = null;
  return value;
};

// Response interceptor to handle errors
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const status = error.response?.status;
    const backendMessage = error.response?.data?.message;
    const code = error.response?.data?.code;

    // 1. License required: hard redirect to activation, never to login
    if (status === 403 && code === 'LICENSE_REQUIRED') {
      if (!window.location.pathname.startsWith('/activate')) {
        window.location.href = '/activate';
      }
      error.friendlyMessage = await translateWithErrorsLoaded('errors:license_required', backendMessage);
      return Promise.reject(error);
    }

    // 2. Handle JWT Expiration / Unauthorized Access. This is a full-session
    //    expiry (24h token, no refresh), so: warn anyone listening (the POS
    //    saves the open cart as a draft), leave a message for the Login page,
    //    and only then redirect. Never a silent localStorage.clear + reload.
    if (status === 401) {
      const alreadyOnLogin = window.location.pathname.includes('/login');
      if (!alreadyOnLogin) {
        window.dispatchEvent(new CustomEvent('app:session-expired', { detail: { message: backendMessage } }));
        const msg = await translateWithErrorsLoaded('errors:session_expired', backendMessage);
        sessionStorage.setItem('app_session_expired_message', msg);
        sessionStorage.setItem('app_session_expired_ts', String(Date.now()));
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        // Small delay lets listeners (e.g. POS draft save) persist before unload
        setTimeout(() => {
          if (!window.location.pathname.includes('/login')) {
            window.location.href = '/login';
          }
        }, 150);
      }
      error.friendlyMessage = await translateWithErrorsLoaded('errors:session_expired', backendMessage);
      return Promise.reject(error);
    }

    // 2b. Role/privilege denied — the session is still valid, the caller just
    // lacks the required role. Don't log the user out; surface the message.
    if (status === 403) {
      error.friendlyMessage = await translateWithErrorsLoaded('errors:forbidden', backendMessage);
      return Promise.reject(error);
    }

    // 3. Handle other known errors with friendly messages
    let friendlyMessage;
    if (!error.response) {
      friendlyMessage = await translateWithErrorsLoaded('errors:cannot_reach_server', backendMessage);
    } else if (status === 409) {
      friendlyMessage = backendMessage || await translateWithErrorsLoaded('errors:conflict', backendMessage);
    } else if (status === 400) {
      friendlyMessage = backendMessage || await translateWithErrorsLoaded('errors:check_form', backendMessage);
    } else if (status >= 500) {
      friendlyMessage = await translateWithErrorsLoaded('errors:unexpected_error', backendMessage);
    } else {
      friendlyMessage = backendMessage || await translateWithErrorsLoaded('errors:generic', backendMessage);
    }

    error.friendlyMessage = friendlyMessage;
    return Promise.reject(error);
  }
);

export default apiClient;
