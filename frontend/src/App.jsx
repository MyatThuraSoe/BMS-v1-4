import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider, MutationCache } from '@tanstack/react-query';
import { ThemeProvider, createTheme, CssBaseline } from '@mui/material';
import { SnackbarProvider } from 'notistack';
import { AuthProvider, useAuth } from './context/AuthContext';
import { notifyError } from './utils/notify';

// Pages
import Login from './pages/Login';
import Dashboard from './pages/Dashboard';
import Products from './pages/Products';
import ProductForm from './pages/ProductForm';
import Categories from './pages/Categories';
import CategoryForm from './pages/CategoryForm';
import Suppliers from './pages/Suppliers';
import SupplierForm from './pages/SupplierForm';
import Purchases from './pages/Purchases';
import PurchaseForm from './pages/PurchaseForm';
import Customers from './pages/Customers';
import CustomerForm from './pages/CustomerForm';
import POS from './pages/POS';
import Sales from './pages/Sales';
import SaleDetail from './pages/SaleDetail';
import ReceiptPreview from './pages/ReceiptPreview';
import Reports from './pages/Reports';
import Inventory from './pages/Inventory';
import StockAdjustment from './pages/StockAdjustment';
import Users from './pages/Users';
import UserForm from './pages/UserForm';
import Settings from './pages/Settings';
import AuditLogs from './pages/AuditLogs';
import ShopInfo from './pages/ShopInfo';
import NotFound from './pages/NotFound';

// Layout
import DashboardLayout from './components/DashboardLayout';
import ProtectedRoute from './components/ProtectedRoute';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 5 * 60 * 1000,
    },
  },
  mutationCache: new MutationCache({
    onError: (error) => {
      notifyError(error.friendlyMessage || 'Something went wrong.');
    },
  }),
});

const theme = createTheme({
  palette: {
    primary: {
      main: '#1976d2',
      light: '#42a5f5',
      dark: '#1565c0',
    },
    secondary: {
      main: '#dc004e',
      light: '#f50057',
      dark: '#c51162',
    },
    background: {
      default: '#f5f5f5',
      paper: '#ffffff',
    },
  },
  typography: {
    fontFamily: '"Roboto", "Helvetica", "Arial", sans-serif',
  },
  shape: {
    borderRadius: 4,
  },
});

function AppRoutes() {
  const { user, loading } = useAuth();

  if (loading) {
    return null;
  }

  return (
    <Routes>
      <Route path="/login" element={!user ? <Login /> : <Navigate to="/dashboard" />} />
      
      <Route path="/" element={<ProtectedRoute><DashboardLayout /></ProtectedRoute>}>
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<Dashboard />} />
        
        {/* Products (Admin & Manager only) */}
        <Route path="products" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Products /></ProtectedRoute>} />
        <Route path="products/new" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><ProductForm /></ProtectedRoute>} />
        <Route path="products/:id" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><ProductForm /></ProtectedRoute>} />
        
        {/* Categories (Admin & Manager only) */}
        <Route path="categories" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Categories /></ProtectedRoute>} />
        <Route path="categories/new" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><CategoryForm /></ProtectedRoute>} />
        <Route path="categories/:id" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><CategoryForm /></ProtectedRoute>} />
        
        {/* Suppliers (Admin & Manager only) */}
        <Route path="suppliers" element={<ProtectedRoute allowedRoles={['ADMIN']}><Suppliers /></ProtectedRoute>} />
        <Route path="suppliers/new" element={<ProtectedRoute allowedRoles={['ADMIN']}><SupplierForm /></ProtectedRoute>} />
        <Route path="suppliers/:id" element={<ProtectedRoute allowedRoles={['ADMIN']}><SupplierForm /></ProtectedRoute>} />
        
        {/* Purchases (Admin & Manager only) */}
        <Route path="purchases" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Purchases /></ProtectedRoute>} />
        <Route path="purchases/new" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><PurchaseForm /></ProtectedRoute>} />
        <Route path="purchases/:id" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><PurchaseForm /></ProtectedRoute>} />
        
        {/* Customers (Admin & Manager only) */}
        <Route path="customers" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Customers /></ProtectedRoute>} />
        <Route path="customers/new" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><CustomerForm /></ProtectedRoute>} />
        <Route path="customers/:id" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><CustomerForm /></ProtectedRoute>} />
        
        {/* POS (all authenticated users) */}
        <Route path="pos" element={<POS />} />
        
        {/* Sales (Admin & Manager only) */}
        <Route path="sales" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Sales /></ProtectedRoute>} />
        <Route path="sales/:id" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><SaleDetail /></ProtectedRoute>} />
        <Route path="receipt/:invoiceNumber" element={<ReceiptPreview />} />
        
        {/* Inventory (Admin & Manager only) */}
        <Route path="inventory" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Inventory /></ProtectedRoute>} />
        <Route path="inventory/adjust" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><StockAdjustment /></ProtectedRoute>} />
        
        {/* Reports (Admin & Manager only) */}
        <Route path="reports" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Reports /></ProtectedRoute>} />
        
        {/* Users (Admin only) */}
        <Route path="users" element={<ProtectedRoute allowedRoles={['ADMIN']}><Users /></ProtectedRoute>} />
        <Route path="users/new" element={<ProtectedRoute allowedRoles={['ADMIN']}><UserForm /></ProtectedRoute>} />
        <Route path="users/:id" element={<ProtectedRoute allowedRoles={['ADMIN']}><UserForm /></ProtectedRoute>} />
        
        {/* Settings (Admin only) */}
        <Route path="settings" element={<ProtectedRoute allowedRoles={['ADMIN']}><Settings /></ProtectedRoute>} />
        
        {/* Shop Info (Admin only) */}
        <Route path="shop-info" element={<ProtectedRoute allowedRoles={['ADMIN']}><ShopInfo /></ProtectedRoute>} />
        
        {/* Audit Logs (Admin only) */}
        <Route path="audit-logs" element={<ProtectedRoute allowedRoles={['ADMIN']}><AuditLogs /></ProtectedRoute>} />
      </Route>
      
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <SnackbarProvider
          maxSnack={3}
          anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
          autoHideDuration={5000}
        >
          <AuthProvider>
            <Router>
              <AppRoutes />
            </Router>
          </AuthProvider>
        </SnackbarProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

export default App;
