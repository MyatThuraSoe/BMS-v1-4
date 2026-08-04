import { useAuth } from '../context/AuthContext';
import { Navigate } from 'react-router-dom';

const ProtectedRoute = ({ children, allowedRoles }) => {
  const { user, isAuthenticated, hasRole, defaultRoute } = useAuth();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }
  

  if (allowedRoles && !hasRole(allowedRoles)) {
    return <Navigate to={defaultRoute} replace />;
  }

  return children;
};

export default ProtectedRoute;
