import { useState, useEffect } from 'react';
import { useNavigate, Link, useLocation, Outlet } from 'react-router-dom';
import {
  Box,
  Drawer,
  AppBar,
  Toolbar,
  Typography,
  IconButton,
  List,
  ListItem,
  ListItemIcon,
  ListItemText,
  ListItemButton,
  Avatar,
  Menu,
  MenuItem,
  Divider,
  CssBaseline,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
  Alert,
  Chip,
  Tooltip,
  GlobalStyles,
} from '@mui/material';
import {
  Dashboard as DashboardIcon,
  ShoppingCart as CartIcon,
  PointOfSale as PosIcon,
  People as CustomersIcon,
  ManageAccounts as UsersIcon,
  Business as SupplierIcon,
  Receipt as ReceiptIcon,
  Assessment as ReportIcon,
  TrendingUp as AnalyticsIcon,
  AccountBalance as AccountingIcon,
  Settings as SettingsIcon,
  Store as ShopInfoIcon,
  History as HistoryIcon,
  Fingerprint as AuditIcon,
  Menu as MenuIcon,
  ChevronLeft as ChevronLeftIcon,
  AccountCircle,
  Logout,
  Lock as LockIcon,
  CloudUpload as CloudUploadIcon,
  AccountBalanceWallet as CashIcon,
  Inventory as InventoryIcon,
  Category as CategoryIcon,
} from '@mui/icons-material';
import { useAuth } from '../context/AuthContext';
import { authService, shopInfoService, shiftService } from '../api/services';
import { useQuery } from '@tanstack/react-query';
import { setCurrencyCode } from '../utils/helpers';

const menuGroups = [
  {
    label: 'Overview',
    items: [
      { text: 'Dashboard', icon: <DashboardIcon />, path: '/dashboard', roles: ['ADMIN', 'MANAGER'], color: 'primary.main' },
    ],
  },
  {
    label: 'Sales',
    items: [
      { text: 'POS', icon: <PosIcon />, path: '/pos', roles: ['ADMIN', 'MANAGER', 'CASHIER'], color: 'success.main' },
      { text: 'Sales', icon: <ReceiptIcon />, path: '/sales', roles: ['ADMIN', 'MANAGER', 'CASHIER'], color: 'info.main' },
      { text: 'Cash Shift', icon: <CashIcon />, path: '/cash-shift', roles: ['ADMIN', 'MANAGER', 'CASHIER'], color: 'warning.main' },
      { text: 'Shift History', icon: <HistoryIcon />, path: '/shift-history', roles: ['ADMIN', 'MANAGER'], color: 'text.secondary' },
    ],
  },
  {
    label: 'Catalog',
    items: [
      { text: 'Products', icon: <InventoryIcon />, path: '/products', roles: ['ADMIN', 'MANAGER'], color: 'primary.main' },
      { text: 'Categories', icon: <CategoryIcon />, path: '/categories', roles: ['ADMIN', 'MANAGER'], color: 'secondary.main' },
    ],
  },
  {
    label: 'Procurement',
    items: [
      { text: 'Suppliers', icon: <SupplierIcon />, path: '/suppliers', roles: ['ADMIN'], color: 'info.main' },
      { text: 'Purchases', icon: <CartIcon />, path: '/purchases', roles: ['ADMIN', 'MANAGER'], color: 'warning.main' },
    ],
  },
  {
    label: 'People',
    items: [
      { text: 'Customers', icon: <CustomersIcon />, path: '/customers', roles: ['ADMIN', 'MANAGER'], color: 'success.main' },
      { text: 'Users', icon: <UsersIcon />, path: '/users', roles: ['ADMIN'], color: 'error.main' },
    ],
  },
  {
    label: 'Insights',
    items: [
      { text: 'Reports', icon: <ReportIcon />, path: '/reports', roles: ['ADMIN', 'MANAGER'], color: 'info.main' },
      { text: 'Analytics', icon: <AnalyticsIcon />, path: '/analytics', roles: ['ADMIN'], color: 'secondary.main' },
      { text: 'Accounting', icon: <AccountingIcon />, path: '/accounting', roles: ['ADMIN'], color: 'success.main' },
    ],
  },
  {
    label: 'Administration',
    items: [
      { text: 'Settings', icon: <SettingsIcon />, path: '/settings', roles: ['ADMIN'], color: 'text.secondary' },
      { text: 'Shop Info', icon: <ShopInfoIcon />, path: '/shop-info', roles: ['ADMIN'], color: 'info.main' },
      { text: 'Backup Settings', icon: <CloudUploadIcon />, path: '/settings/backup', roles: ['ADMIN'], color: 'warning.main' },
      { text: 'Audit Logs', icon: <AuditIcon />, path: '/audit-logs', roles: ['ADMIN'], color: 'error.main' },
    ],
  },
];

const menuItems = menuGroups.flatMap((g) => g.items);

const getPageTitle = (pathname) => {
  const exactMatch = menuItems.find((item) => item.path === pathname);
  if (exactMatch) return exactMatch.text;

  const segments = pathname.split('/').filter(Boolean);
  
  if (segments.length === 0) return 'Dashboard';

  const section = menuItems.find((item) => item.path === '/' + segments[0]);
  const sectionName = section?.text || segments[0] || 'Dashboard';

  if (segments.length === 1) return sectionName;
  if (segments[1] === 'new') return `New ${sectionName.replace(/s$/, '')}`;
  if (segments[segments.length - 1] === 'edit') return `Edit ${sectionName.replace(/s$/, '')}`;
  
  return `${sectionName.replace(/s$/, '')} Details`;
};

const DashboardLayout = ({ children }) => {
  const [mobileOpen, setMobileOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  
  const expandedWidth = 240;
  const collapsedWidth = 68;
  const currentDrawerWidth = collapsed ? collapsedWidth : expandedWidth;

  const [anchorEl, setAnchorEl] = useState(null);
  const [changePasswordOpen, setChangePasswordOpen] = useState(false);
  const [cpCurrentPassword, setCpCurrentPassword] = useState('');
  const [cpNewPassword, setCpNewPassword] = useState('');
  const [cpConfirmPassword, setCpConfirmPassword] = useState('');
  const [cpError, setCpError] = useState('');
  const [cpSuccess, setCpSuccess] = useState('');
  const [cpLoading, setCpLoading] = useState(false);
  
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuth();

  const { data: shopInfoData } = useQuery({
    queryKey: ['shopInfo'],
    queryFn: () => shopInfoService.get(),
  });

  const { data: currentShiftData } = useQuery({
    queryKey: ['currentShift'],
    queryFn: () => shiftService.getCurrentShift(),
    refetchInterval: 30000,
  });

  const currentShift = currentShiftData?.data;
  const shopName = shopInfoData?.data?.shopName;

  // Keep the app-wide currency sign in sync with the Shop Info setting
  useEffect(() => {
    const currency = shopInfoData?.data?.currency;
    if (currency) setCurrencyCode(currency);
  }, [shopInfoData]);

  const handleDrawerToggle = () => setMobileOpen(!mobileOpen);
  const handleMenuOpen = (event) => setAnchorEl(event.currentTarget);
  const handleMenuClose = () => setAnchorEl(null);
  const handleLogout = () => { logout(); navigate('/login'); };

  const handleChangePasswordOpen = () => {
    setAnchorEl(null);
    setCpCurrentPassword(''); setCpNewPassword(''); setCpConfirmPassword('');
    setCpError(''); setCpSuccess('');
    setChangePasswordOpen(true);
  };

  const handleChangePasswordSubmit = async () => {
    setCpError(''); setCpSuccess('');
    if (cpNewPassword !== cpConfirmPassword) { setCpError('New passwords do not match'); return; }
    if (cpNewPassword.length < 6) { setCpError('New password must be at least 6 characters'); return; }
    setCpLoading(true);
    try {
      await authService.changePassword(cpCurrentPassword, cpNewPassword);
      setCpSuccess('Password changed successfully');
      setTimeout(() => setChangePasswordOpen(false), 1500);
    } catch (err) {
      setCpError(err.response?.data?.message || 'Failed to change password');
    } finally {
      setCpLoading(false);
    }
  };

  const canAccessItem = (item) => {
    if (!user || !item.roles) return false;
    const userRoles = user.roles?.map(r => r?.name || r).filter(Boolean) || [];
    return item.roles.some(role => userRoles.includes(role));
  };

  const drawer = (
    <Box>
      <Toolbar sx={{ justifyContent: collapsed ? 'center' : 'space-between', px: collapsed ? 1 : 2 }}>
        {!collapsed && (
          <Typography variant="h6" noWrap component="div" sx={{ fontWeight: 'bold' }}>
            BMS v1
          </Typography>
        )}
        <IconButton onClick={() => setCollapsed(!collapsed)} size="small">
          {collapsed ? <ChevronLeftIcon sx={{ transform: 'rotate(180deg)' }} /> : <ChevronLeftIcon />}
        </IconButton>
      </Toolbar>
      <Divider />
      {menuGroups.map((group) => {
        const visibleItems = group.items.filter(canAccessItem);
        if (visibleItems.length === 0) return null;
        return (
          <Box key={group.label}>
            {!collapsed && (
              <Typography
                variant="caption"
                sx={{ display: 'block', px: 2, pt: 2, pb: 0.5, color: 'text.disabled', fontWeight: 'bold', letterSpacing: 0.5 }}
              >
                {group.label.toUpperCase()}
              </Typography>
            )}
            <List dense>
              {visibleItems.map((item) => {
                const button = (
                  <ListItemButton
                    component={Link}
                    to={item.path}
                    selected={location.pathname === item.path}
                    sx={{
                      justifyContent: collapsed ? 'center' : 'flex-start',
                      px: collapsed ? 1.5 : 2,
                      '&.Mui-selected': { backgroundColor: 'primary.main', color: 'primary.contrastText' },
                      '&.Mui-selected:hover': { backgroundColor: 'primary.dark' },
                    }}
                  >
                    <ListItemIcon
                      sx={{
                        minWidth: collapsed ? 0 : 40,
                        // <-- APPLIES THE CUSTOM COLOR, BUT OVERRIDES TO WHITE WHEN SELECTED
                        color: location.pathname === item.path ? 'inherit' : item.color,
                        justifyContent: 'center',
                      }}
                    >
                      {item.icon}
                    </ListItemIcon>
                    {!collapsed && <ListItemText primary={item.text} />}
                  </ListItemButton>
                );
                return (
                  <ListItem key={item.text} disablePadding sx={{ display: 'block' }}>
                    {collapsed ? (
                      <Tooltip title={item.text} placement="right" arrow>
                        <Box component="span" sx={{ display: 'block' }}>
                          {button}
                        </Box>
                      </Tooltip>
                    ) : button}
                  </ListItem>
                );
              })}
            </List>
          </Box>
        );
      })}
    </Box>
  );

  return (
    <Box sx={{ display: 'flex' }}>
      <CssBaseline />
      
      <GlobalStyles
        styles={(theme) => ({
          '::-webkit-scrollbar': {
            width: '8px',
            height: '8px',
          },
          '::-webkit-scrollbar-track': {
            background: 'transparent',
          },
          '::-webkit-scrollbar-thumb': {
            background: theme.palette.primary.main,
            borderRadius: '4px',
          },
          '::-webkit-scrollbar-thumb:hover': {
            background: theme.palette.primary.dark,
          },
        })}
      />

      <AppBar
        position="fixed"
        sx={{
          width: { sm: `calc(100% - ${currentDrawerWidth}px)` },
          ml: { sm: `${currentDrawerWidth}px` },
        }}
      >
        <Toolbar sx={{ gap: 1, minHeight: { xs: 56, sm: 64 } }}>
          <IconButton 
            color="inherit" 
            edge="start" 
            onClick={handleDrawerToggle} 
            sx={{ mr: 1, display: { sm: 'none' }, color: 'white', p: 1 }}
          >
            <MenuIcon />
          </IconButton>
          <Typography
            variant="h6"
            noWrap
            component="div"
            sx={{ flexGrow: 1, fontSize: { xs: '1rem', sm: '1.25rem' }, minWidth: 0 }}
          >
            {getPageTitle(location.pathname)}
          </Typography>
          <Box
            sx={{
              display: { xs: 'none', md: 'flex' },
              alignItems: 'center',
              gap: 1,
              minWidth: 0,
            }}
          >
            <Typography variant="body2" noWrap component="div" sx={{ fontSize: '14px' }}>
              {shopName || 'BMS'}
            </Typography>
            {currentShift && (
              <Chip
                icon={<CashIcon />}
                label={`Shift: Open since ${new Date(currentShift.openingTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`}
                color="success"
                size="small"
                variant="filled"
                sx={{ ml: 1 }}
              />
            )}
          </Box>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            {currentShift && (
              <Chip
                icon={<CashIcon />}
                label="Open"
                color="success"
                size="small"
                variant="filled"
                sx={{ display: { xs: 'inline-flex', md: 'none' } }}
              />
            )}
            <Typography variant="body2" sx={{ display: { xs: 'none', sm: 'block' } }}>{user?.username}</Typography>
            <IconButton onClick={handleMenuOpen} size="small">
              <Avatar sx={{ width: 32, height: 32, bgcolor: 'secondary.main' }}>
                {user?.username?.charAt(0).toUpperCase()}
              </Avatar>
            </IconButton>
          </Box>
          <Menu anchorEl={anchorEl} open={Boolean(anchorEl)} onClose={handleMenuClose}>
            <MenuItem disabled><AccountCircle sx={{ mr: 1 }} />{user?.username}</MenuItem>
            <Divider />
            <MenuItem onClick={handleChangePasswordOpen}><LockIcon sx={{ mr: 1 }} />Change Password</MenuItem>
            <MenuItem onClick={handleLogout}><Logout sx={{ mr: 1 }} />Logout</MenuItem>
          </Menu>
        </Toolbar>
      </AppBar>
      
      <Box component="nav" sx={{ width: { sm: currentDrawerWidth }, flexShrink: { sm: 0 } }}>
        <Drawer
          variant="temporary"
          open={mobileOpen}
          onClose={handleDrawerToggle}
          ModalProps={{ keepMounted: true }}
          sx={{
            display: { xs: 'block', sm: 'none' },
            '& .MuiDrawer-paper': { boxSizing: 'border-box', width: expandedWidth },
          }}
        >
          {drawer}
        </Drawer>
        <Drawer
          variant="permanent"
          sx={{
            display: { xs: 'none', sm: 'block' },
            '& .MuiDrawer-paper': { boxSizing: 'border-box', width: currentDrawerWidth },
          }}
          open
        >
          {drawer}
        </Drawer>
      </Box>
      
      <Box
        component="main"
        sx={{
          flexGrow: 1,
          p: { xs: 2, sm: 3 },
          mt: { xs: 7, sm: 8 },
          width: { sm: `calc(100% - ${currentDrawerWidth}px)` },
        }}
      >
        {children || <Outlet />}
      </Box>

      <Dialog open={changePasswordOpen} onClose={() => setChangePasswordOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Change Password</DialogTitle>
        <DialogContent>
          {cpError && <Alert severity="error" sx={{ mb: 2 }}>{cpError}</Alert>}
          {cpSuccess && <Alert severity="success" sx={{ mb: 2 }}>{cpSuccess}</Alert>}
          <TextField fullWidth label="Current Password" type="password" value={cpCurrentPassword} onChange={(e) => setCpCurrentPassword(e.target.value)} sx={{ mb: 2, mt: 1 }} />
          <TextField fullWidth label="New Password" type="password" value={cpNewPassword} onChange={(e) => setCpNewPassword(e.target.value)} sx={{ mb: 2 }} />
          <TextField fullWidth label="Confirm New Password" type="password" value={cpConfirmPassword} onChange={(e) => setCpConfirmPassword(e.target.value)} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setChangePasswordOpen(false)}>Cancel</Button>
          <Button onClick={handleChangePasswordSubmit} variant="contained" disabled={cpLoading}>
            {cpLoading ? 'Changing...' : 'Change Password'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default DashboardLayout;