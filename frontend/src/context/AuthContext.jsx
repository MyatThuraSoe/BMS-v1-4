import { createContext, useContext, useState, useEffect } from 'react';
import { authService } from '../api/services';

const AuthContext = createContext(null);

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const currentUser = authService.getCurrentUser();
    setUser(currentUser);
    setLoading(false);
  }, []);

  const login = async (username, password) => {
    const response = await authService.login(username, password);
    if (response.success) {
      setUser(response.data.user);
      return response;
    }
    throw new Error(response.message || 'Login failed');
  };

  const logout = () => {
    authService.logout();
    setUser(null);
  };

  const hasRole = (roles) => {
    if (!user) return false;
    if (!Array.isArray(roles)) roles = [roles];
    return user.roles.some(role => roles.includes(role.name || role));
  };

  const isAdmin = () => hasRole('ADMIN');
  const isManager = () => hasRole(['ADMIN', 'MANAGER']);
  const isCashier = () => hasRole(['ADMIN', 'MANAGER', 'CASHIER']);

  const value = {
    user,
    loading,
    login,
    logout,
    hasRole,
    isAdmin,
    isManager,
    isCashier,
    isAuthenticated: !!user,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};
