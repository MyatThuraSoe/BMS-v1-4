import { lazy, Suspense } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider, createTheme, CssBaseline } from '@mui/material';
import { Box, Grid, Skeleton } from '@mui/material';
import { SnackbarProvider } from 'notistack';
import { AuthProvider, useAuth } from './context/AuthContext';

// Auth entry screens stay eager (first paint); every other page is code-split
// so the initial download stays small regardless of how many features exist.
import Login from './pages/Login';
import SetupFirstAdmin from './pages/SetupFirstAdmin';
import Activate from './pages/Activate';
import NotFound from './pages/NotFound';

const About = lazy(() => import('./pages/About'));
const Dashboard = lazy(() => import('./pages/Dashboard'));
const Products = lazy(() => import('./pages/Products'));
const ProductForm = lazy(() => import('./pages/ProductForm'));
const ProductDetail = lazy(() => import('./pages/ProductDetail'));
const Categories = lazy(() => import('./pages/Categories'));
const CategoryForm = lazy(() => import('./pages/CategoryForm'));
const Suppliers = lazy(() => import('./pages/Suppliers'));
const SupplierForm = lazy(() => import('./pages/SupplierForm'));
const Purchases = lazy(() => import('./pages/Purchases'));
const PurchaseForm = lazy(() => import('./pages/PurchaseForm'));
const Customers = lazy(() => import('./pages/Customers'));
const CustomerDetails = lazy(() => import('./pages/CustomerDetails'));
const CustomerForm = lazy(() => import('./pages/CustomerForm'));
const POS = lazy(() => import('./pages/POS'));
const Sales = lazy(() => import('./pages/Sales'));
const SaleDetail = lazy(() => import('./pages/SaleDetail'));
const ReceiptPreview = lazy(() => import('./pages/ReceiptPreview'));
const Reports = lazy(() => import('./pages/Reports'));
const Analytics = lazy(() => import('./pages/Analytics'));
const Accounting = lazy(() => import('./pages/Accounting'));
const StockAdjustment = lazy(() => import('./pages/StockAdjustment'));
const Inventory = lazy(() => import('./pages/Inventory'));
const Users = lazy(() => import('./pages/Users'));
const UserForm = lazy(() => import('./pages/UserForm'));
const UserUpdate = lazy(() => import('./pages/UserUpdate'));
const Settings = lazy(() => import('./pages/Settings'));
const AuditLogs = lazy(() => import('./pages/AuditLogs'));
const ShopInfo = lazy(() => import('./pages/ShopInfo'));
const ReceiptCustomization = lazy(() => import('./pages/ReceiptCustomization'));
const BackupSettings = lazy(() => import('./pages/BackupSettings'));
const SupplierDetails = lazy(() => import('./pages/SupplierDetails'));
const CashShift = lazy(() => import('./pages/CashShift'));
const ShiftHistory = lazy(() => import('./pages/ShiftHistory'));
const AccountsReceivable = lazy(() => import('./pages/AccountsReceivable'));
const Orders = lazy(() => import('./pages/Orders'));
const Drafts = lazy(() => import('./pages/Drafts'));

// Layout
import DashboardLayout from './components/DashboardLayout';
import ProtectedRoute from './components/ProtectedRoute';
import PrintJobWorker from './components/PrintJobWorker';

// Skeleton fallback for lazy route chunks: mimics a generic page (title +
// summary cards + chart block) so first navigation reads as "content loading"
// rather than an app refresh. Chunks are also idle-prefetched after login,
// so this rarely appears at all.
const RouteSkeleton = () => (
  <Box sx={{ p: { xs: 2, sm: 3 } }}>
    <Skeleton variant="rounded" width="35%" height={34} sx={{ mb: 3 }} />
    <Grid container spacing={2} sx={{ mb: 3 }}>
      {[0, 1, 2, 3].map((i) => (
        <Grid item xs={6} md={3} key={i}>
          <Skeleton variant="rounded" height={88} />
        </Grid>
      ))}
    </Grid>
    <Grid container spacing={2}>
      <Grid item xs={12} md={7}>
        <Skeleton variant="rounded" height={220} />
      </Grid>
      <Grid item xs={12} md={5}>
        <Skeleton variant="rounded" height={220} />
      </Grid>
    </Grid>
  </Box>
);

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 0,
    },
  },
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
    // Mobile responsiveness defaults: every dialog adapts to screen width
    // unless a page explicitly overrides maxWidth/fullWidth.
    MuiDialog: {
      defaultProps: { fullWidth: true, maxWidth: 'sm' },
    },
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
    // Safety net only — the REAL per-route boundary lives inside
    // DashboardLayout (around <Outlet />) so the menu bar / app bar never
    // unmount while a lazy page chunk loads.
    <Suspense fallback={<RouteSkeleton />}>
      <Routes>
      <Route path="/login" element={!user ? <Login /> : <Navigate to={defaultRoute} />} />
      <Route path="/setup" element={!user ? <SetupFirstAdmin /> : <Navigate to={defaultRoute} />} />

      {/* liscene key  */}
        <Route path="/activate" element={<Activate />} />
      
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
        
        {/* Orders (all roles) */}
        <Route path="orders" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER', 'CASHIER']}><Orders /></ProtectedRoute>} />
        <Route path="drafts" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER', 'CASHIER']}><Drafts /></ProtectedRoute>} />
        
        {/* Inventory Center (Admin & Manager only) */}
        <Route path="inventory" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Inventory /></ProtectedRoute>} />
        {/* Inventory Adjustments (Admin & Manager only) */}
        <Route path="inventory/adjust" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><StockAdjustment /></ProtectedRoute>} />

        
        {/* Reports (Admin & Manager only) */}
        <Route path="reports" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><Reports /></ProtectedRoute>} />
        <Route path="analytics" element={<ProtectedRoute allowedRoles={['ADMIN']}><Analytics /></ProtectedRoute>} />
        <Route path="accounting" element={<ProtectedRoute allowedRoles={['ADMIN']}><Accounting /></ProtectedRoute>} />
        <Route path="accounts-receivable" element={<ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}><AccountsReceivable /></ProtectedRoute>} />
        
        {/* Users (Admin only) */}
        <Route path="users" element={<ProtectedRoute allowedRoles={['ADMIN']}><Users /></ProtectedRoute>} />
        <Route path="users/new" element={<ProtectedRoute allowedRoles={['ADMIN']}><UserForm /></ProtectedRoute>} />
        <Route path="users/:id" element={<ProtectedRoute allowedRoles={['ADMIN']}><UserUpdate /></ProtectedRoute>} />
        
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
        
        <Route path="data" element={<Navigate to="/settings/backup" replace />} />
        <Route path="shop-info" element={<ProtectedRoute allowedRoles={['ADMIN']}><ShopInfo /></ProtectedRoute>} />
        <Route path="receipt-customization" element={<ProtectedRoute allowedRoles={['ADMIN']}><ReceiptCustomization /></ProtectedRoute>} />
        
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
    </Suspense>
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
              <PrintJobWorker />
              <AppRoutes />
            </Router>
          </AuthProvider>
        </SnackbarProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

export default App;
