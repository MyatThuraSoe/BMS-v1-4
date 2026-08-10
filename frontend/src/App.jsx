import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider, MutationCache } from '@tanstack/react-query';
import { ThemeProvider, createTheme, CssBaseline } from '@mui/material';
import { SnackbarProvider } from 'notistack';
import { AuthProvider, useAuth } from './context/AuthContext';
import { notifyError } from './utils/notify';

// Pages
import About from './pages/About';
import Login from './pages/Login';
import SetupFirstAdmin from './pages/SetupFirstAdmin';
import Dashboard from './pages/Dashboard';
import Products from './pages/Products';
import ProductForm from './pages/ProductForm';
import ProductDetail from './pages/ProductDetail';
import Categories from './pages/Categories';
import CategoryForm from './pages/CategoryForm';
import Suppliers from './pages/Suppliers';
import SupplierForm from './pages/SupplierForm';
import Purchases from './pages/Purchases';
import PurchaseForm from './pages/PurchaseForm';
import Customers from './pages/Customers';
import CustomerDetails from './pages/CustomerDetails';
import CustomerForm from './pages/CustomerForm';
import POS from './pages/POS';
import Sales from './pages/Sales';
import SaleDetail from './pages/SaleDetail';
import ReceiptPreview from './pages/ReceiptPreview';
import Reports from './pages/Reports';
import Analytics from './pages/Analytics';
import Accounting from './pages/Accounting';
import StockAdjustment from './pages/StockAdjustment';
import Users from './pages/Users';
import UserForm from './pages/UserForm';
import Settings from './pages/Settings';
import AuditLogs from './pages/AuditLogs';
import ShopInfo from './pages/ShopInfo';
import NotFound from './pages/NotFound';

import BackupSettings from './pages/BackupSettings';
import SupplierDetails from './pages/SupplierDetails';
import CashShift from './pages/CashShift';
import ShiftHistory from './pages/ShiftHistory';

// Layout
import DashboardLayout from './components/DashboardLayout';
import ProtectedRoute from './components/ProtectedRoute';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 0,
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
      main: '#2B6E4F',      // grocer's green — primary actions
      light: '#4A8A6C',
      dark: '#1F5239',
    },
    secondary: {
      main: '#B8862E',      // muted brass/gold — price emphasis, used sparingly
      light: '#CBA054',
      dark: '#96701F',
    },
    error: {
      main: '#B23A2E',      // brick red — reserved for stock warnings/errors only
    },
    background: {
      default: '#F3F5F1',   // cool paper-white, not cream
      paper: '#FFFFFF',
    },
    text: {
      primary: '#1C2620',   // deep forest-charcoal ink, not pure black
      secondary: '#5B655D',
    },
    divider: '#DEDFD6',
  },
  typography: {
    fontFamily: '"Noto Sans", "Noto Sans Myanmar", "Noto Sans Thai", "Noto Sans JP", "Work Sans", "Helvetica", "Arial", sans-serif',
    h1: { fontFamily: '"Fraunces", serif', fontWeight: 600 },
    h2: { fontFamily: '"Fraunces", serif', fontWeight: 600 },
    h3: { fontFamily: '"Fraunces", serif', fontWeight: 600 },
    h4: { fontFamily: '"Fraunces", serif', fontWeight: 600 },
    h5: { fontFamily: '"Fraunces", serif', fontWeight: 600 },
    h6: { fontFamily: '"Fraunces", serif', fontWeight: 600 },
    button: { fontFamily: '"Noto Sans", "Noto Sans Myanmar", "Noto Sans Thai", "Noto Sans JP", "Work Sans", sans-serif', fontWeight: 600, textTransform: 'none' },
  },
  shape: {
    borderRadius: 10,
  },
  components: {
    MuiButton: {
      styleOverrides: {
        root: { borderRadius: 8, paddingTop: 10, paddingBottom: 10 },
        sizeLarge: { paddingTop: 14, paddingBottom: 14, fontSize: '1rem' },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: { borderRadius: 999, fontWeight: 500 },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: { backgroundImage: 'none' },
      },
    },
  },
});


function AppRoutes() {
  const { user, loading, defaultRoute } = useAuth();

  if (loading) {
    return null;
  }

  return (
    <Routes>
      <Route path="/login" element={!user ? <Login /> : <Navigate to={defaultRoute} />} />
      <Route path="/setup" element={!user ? <SetupFirstAdmin /> : <Navigate to={defaultRoute} />} />
      
      <Route path="/" element={<ProtectedRoute><DashboardLayout /></ProtectedRoute>}>
        <Route index element={<Navigate to={defaultRoute} replace />} />
        <Route path="dashboard" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Dashboard /></ProtectedRoute>} />
        
        {/* Products (Admin & Manager only) */}
        <Route path="products" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Products /></ProtectedRoute>} />
        <Route path="products/new" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><ProductForm /></ProtectedRoute>} />
        <Route path="products/:id" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><ProductDetail /></ProtectedRoute>} />
        <Route path="products/:id/edit" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><ProductForm /></ProtectedRoute>} />
        
        {/* Categories (Admin & Manager only) */}
        <Route path="categories" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Categories /></ProtectedRoute>} />
        <Route path="categories/new" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><CategoryForm /></ProtectedRoute>} />
        <Route path="categories/:id" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><CategoryForm /></ProtectedRoute>} />
        
        {/* Suppliers (Admin & Manager only) */}
        <Route path="suppliers" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Suppliers /></ProtectedRoute>} />
        <Route path="suppliers/new" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><SupplierForm /></ProtectedRoute>} />
        <Route path="suppliers/:id" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><SupplierDetails /></ProtectedRoute>} />
        <Route path="suppliers/:id/edit" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><SupplierForm /></ProtectedRoute>} />

        {/* Purchases (Admin & Manager only) */}
        <Route path="purchases" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Purchases /></ProtectedRoute>} />
        <Route path="purchases/new" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><PurchaseForm /></ProtectedRoute>} />
        <Route path="purchases/:id" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><PurchaseForm /></ProtectedRoute>} />
        
       
        {/* Customers (Admin & Manager only) */}
        <Route path="customers" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Customers /></ProtectedRoute>} />
        <Route path="customers/new" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><CustomerForm /></ProtectedRoute>} />
        <Route path="customers/:id" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><CustomerDetails /></ProtectedRoute>} />
        <Route path="customers/:id/edit" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><CustomerForm /></ProtectedRoute>} />

        {/* POS (all authenticated users) */}
        <Route path="pos" element={<POS />} />
        
        {/* Sales (all authenticated; void/delete hidden for cashiers in the UI) */}
        <Route path="sales" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER', 'CASHIER']}><Sales /></ProtectedRoute>} />
        <Route path="sales/:id" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER', 'CASHIER']}><SaleDetail /></ProtectedRoute>} />
        <Route path="receipt/:invoiceNumber" element={<ReceiptPreview />} />
        
        {/* Inventory Adjustments (Admin & Manager only) */}
        <Route path="inventory/adjust" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><StockAdjustment /></ProtectedRoute>} />

        
        {/* Reports (Admin & Manager only) */}
        <Route path="reports" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Reports /></ProtectedRoute>} />
        <Route path="analytics" element={<ProtectedRoute allowedRoles={['ADMIN']}><Analytics /></ProtectedRoute>} />
        <Route path="accounting" element={<ProtectedRoute allowedRoles={['ADMIN']}><Accounting /></ProtectedRoute>} />
        
        {/* Users (Admin only) */}
        <Route path="users" element={<ProtectedRoute allowedRoles={['ADMIN']}><Users /></ProtectedRoute>} />
        <Route path="users/new" element={<ProtectedRoute allowedRoles={['ADMIN']}><UserForm /></ProtectedRoute>} />
        <Route path="users/:id" element={<ProtectedRoute allowedRoles={['ADMIN']}><UserForm /></ProtectedRoute>} />
        
        {/* Settings (Admin only) */}
        <Route path="settings" element={<ProtectedRoute allowedRoles={['ADMIN']}><Settings /></ProtectedRoute>} />
        <Route 
          path="settings/backup" 
          element={
            <ProtectedRoute allowedRoles={['ADMIN']}>
              <BackupSettings />
            </ProtectedRoute>
          } 
        />
        <Route path="shop-info" element={<ProtectedRoute allowedRoles={['ADMIN']}><ShopInfo /></ProtectedRoute>} />
        
        {/* Audit Logs (Admin only) */}
        <Route path="audit-logs" element={<ProtectedRoute allowedRoles={['ADMIN']}><AuditLogs /></ProtectedRoute>} />

        {/* Cash Shift (all roles) */}
        <Route path="cash-shift" element={<CashShift />} />
        <Route path="shift-history" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><ShiftHistory /></ProtectedRoute>} />

        {/* About Page (all roles) */}
        <Route path="about" element={<About />} />
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
