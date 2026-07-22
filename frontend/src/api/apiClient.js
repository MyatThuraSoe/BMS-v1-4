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
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.location.href = '/login';
      return Promise.reject(error);
    }

    const status = error.response?.status;
    const backendMessage = error.response?.data?.message;

    let friendlyMessage;
    if (!error.response) {
      friendlyMessage = 'Cannot reach the server. Check your connection and try again.';
    } else if (status === 403) {
      friendlyMessage = "You don't have permission to do that.";
    } else if (status === 404) {
      friendlyMessage = backendMessage || 'That record could not be found — it may have been deleted.';
    } else if (status === 409) {
      friendlyMessage = backendMessage || 'That action conflicts with existing data (e.g. a duplicate or a record still in use elsewhere).';
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
