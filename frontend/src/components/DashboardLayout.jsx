import { Suspense, useState, useEffect } from 'react';
import { useNavigate, Link, useLocation, Outlet } from 'react-router-dom';
import {
  Box,
  Grid,
  Skeleton,
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
  ListAlt as ListAltIcon,
  Drafts as DraftsIcon,
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
  Payments as PaymentsIcon,
  Inventory as InventoryIcon,
  Warehouse as WarehouseIcon,
  Category as CategoryIcon,
  Info as InfoIcon,
  Fullscreen as FullscreenIcon,
  FullscreenExit as FullscreenExitIcon,

} from '@mui/icons-material';

import { useAuth } from '../context/AuthContext';
import { authService, licenseService, shopInfoService, shiftService } from '../api/services';
import { useQuery } from '@tanstack/react-query';
import { setCurrencyCode } from '../utils/helpers';
import LanguageSwitcher from './LanguageSwitcher';
import useShopConfig from '../hooks/useShopConfig';
import { preloadRouteChunks } from '../utils/preloadRouteChunks';
import { useTranslation } from 'react-i18next';
import { notifyWarning } from '../utils/notify';

// Content-area skeleton shown while a lazy route chunk loads on FIRST visit.
// Lives inside the persistent shell so the drawer/app bar never unmount.
const ContentSkeleton = () => (
  <Box>
    <Skeleton variant="rounded" width="35%" height={32} sx={{ mb: 3 }} />
    <Grid container spacing={2} sx={{ mb: 3 }}>
      {[0, 1].map((i) => (
        <Grid item xs={12} sm={6} key={i}>
          <Skeleton variant="rounded" height={140} />
        </Grid>
      ))}
    </Grid>
    <Skeleton variant="rounded" height={200} />
  </Box>
);

const menuGroups = [
  {
    labelKey: 'group_overview',
    items: [
      { textKey: 'dashboard', icon: <DashboardIcon />, path: '/dashboard', roles: ['ADMIN', 'MANAGER'], color: 'primary.main' },
    ],
  },
  {
    labelKey: 'group_sales',
    items: [
      { textKey: 'pos', icon: <PosIcon />, path: '/pos', roles: ['ADMIN', 'MANAGER', 'CASHIER'], color: 'success.main' },
      { textKey: 'orders', icon: <ListAltIcon />, path: '/orders', roles: ['ADMIN', 'MANAGER', 'CASHIER'], color: 'text.secondary' },
      { textKey: 'drafts', icon: <DraftsIcon />, path: '/drafts', roles: ['ADMIN', 'MANAGER', 'CASHIER'], color: 'secondary.main' },
      { textKey: 'sales', icon: <ReceiptIcon />, path: '/sales', roles: ['ADMIN', 'MANAGER', 'CASHIER'], color: 'info.main' },
      { textKey: 'cash_shift', icon: <CashIcon />, path: '/cash-shift', roles: ['ADMIN', 'MANAGER', 'CASHIER'], color: 'warning.main' },
      { textKey: 'shift_history', icon: <HistoryIcon />, path: '/shift-history', roles: ['ADMIN', 'MANAGER'], color: 'text.secondary' },
      { textKey: 'accounts_receivable', icon: <PaymentsIcon />, path: '/accounts-receivable', roles: ['ADMIN', 'MANAGER'], color: 'warning.main' },
    ],
  },
  {
    labelKey: 'group_catalog',
    items: [
      { textKey: 'inventory', icon: <WarehouseIcon />, path: '/inventory', roles: ['ADMIN', 'MANAGER'], color: 'warning.main' },
      { textKey: 'products', icon: <InventoryIcon />, path: '/products', roles: ['ADMIN', 'MANAGER'], color: 'primary.main' },
      { textKey: 'categories', icon: <CategoryIcon />, path: '/categories', roles: ['ADMIN', 'MANAGER'], color: 'secondary.main' },
    ],
  },
  {
    labelKey: 'group_procurement',
    items: [
      { textKey: 'purchases', icon: <CartIcon />, path: '/purchases', roles: ['ADMIN', 'MANAGER'], color: 'warning.main' },
    ],
  },
  {
    labelKey: 'group_people',
    items: [
      { textKey: 'customers', icon: <CustomersIcon />, path: '/customers', roles: ['ADMIN', 'MANAGER'], color: 'success.main' },
      { textKey: 'suppliers', icon: <SupplierIcon />, path: '/suppliers', roles: ['ADMIN'], color: 'info.main' },
    ],
  },
  {
    labelKey: 'group_insights',
    items: [
      { textKey: 'reports', icon: <ReportIcon />, path: '/reports', roles: ['ADMIN', 'MANAGER'], color: 'info.main' },
      { textKey: 'analytics', icon: <AnalyticsIcon />, path: '/analytics', roles: ['ADMIN'], color: 'secondary.main' },
      { textKey: 'accounting', icon: <AccountingIcon />, path: '/accounting', roles: ['ADMIN'], color: 'success.main' },
    ],
  },
  {
    labelKey: 'group_administration',
    items: [
      { textKey: 'settings', icon: <SettingsIcon />, path: '/settings', roles: ['ADMIN'], color: 'text.secondary' },
      { textKey: 'users', icon: <UsersIcon />, path: '/users', roles: ['ADMIN'], color: 'error.main' },
      { textKey: 'shop_info', icon: <ShopInfoIcon />, path: '/shop-info', roles: ['ADMIN'], color: 'info.main' },
      { textKey: 'receipt_customization', icon: <ReceiptIcon />, path: '/receipt-customization', roles: ['ADMIN'], color: 'secondary.main' },
      { textKey: 'backup_settings', icon: <CloudUploadIcon />, path: '/settings/backup', roles: ['ADMIN'], color: 'warning.main' },
      { textKey: 'audit_logs', icon: <AuditIcon />, path: '/audit-logs', roles: ['ADMIN'], color: 'error.main' },
    ],
  },
  {
    labelKey: 'app_info',
    items: [
      { textKey: 'about', icon: <InfoIcon />, path: '/about', roles: ['ADMIN', 'MANAGER', 'CASHIER'], color: 'primary.main' },
    ],
  },

];

const menuItems = menuGroups.flatMap((g) => g.items);

const getPageTitle = (pathname, t) => {
  const exactMatch = menuItems.find((item) => item.path === pathname);
  if (exactMatch) return t(exactMatch.textKey);

  const segments = pathname.split('/').filter(Boolean);
  
  if (segments.length === 0) return t('dashboard');

  const section = menuItems.find((item) => item.path === '/' + segments[0]);
  const sectionName = section ? t(section.textKey) : segments[0] || t('dashboard');

  if (segments.length === 1) return sectionName;
  if (segments[1] === 'new') return t('new_entity', { name: sectionName.replace(/s$/, '') });
  if (segments[segments.length - 1] === 'edit') return t('edit_entity', { name: sectionName.replace(/s$/, '') });
  
  return t('entity_details', { name: sectionName.replace(/s$/, '') });
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
  const { t } = useTranslation('nav');

  const { data: shopInfoData } = useShopConfig();

  const { data: currentShiftData } = useQuery({
    queryKey: ['currentShift'],
    queryFn: () => shiftService.getCurrentShift(),
    refetchInterval: 30000,
  });

  const currentShift = currentShiftData?.data;
  const shopName = shopInfoData?.data?.shopName;
  const [remindedShiftId, setRemindedShiftId] = useState(null);

  useEffect(() => {
    if (!currentShift?.id || !currentShift.openingTime) {
      setRemindedShiftId(null);
      return;
    }

    const openedAt = new Date(currentShift.openingTime).getTime();
    const isOlderThanTwelveHours = Number.isFinite(openedAt)
      && Date.now() - openedAt >= 12 * 60 * 60 * 1000;

    if (isOlderThanTwelveHours && remindedShiftId !== currentShift.id) {
      notifyWarning(t('cash:shift_overdue'));
      setRemindedShiftId(currentShift.id);
    }
  }, [currentShift, remindedShiftId, t]);


  const { data: licData } = useQuery({
    queryKey: ['license-status'],
    queryFn: () => licenseService.getStatus(),
});
const lic = licData?.data;

  // Warm all route chunks during idle so first menu clicks never flash
  useEffect(() => { preloadRouteChunks(); }, []);

  // Keep the app-wide currency sign in sync with the Shop Info setting
  useEffect(() => {
    const currency = shopInfoData?.data?.currency;
    if (currency) setCurrencyCode(currency);
  }, [shopInfoData]);

  const handleDrawerToggle = () => setMobileOpen(!mobileOpen);
  const handleMenuOpen = (event) => setAnchorEl(event.currentTarget);
  const handleMenuClose = () => setAnchorEl(null);
  const handleLogout = () => { logout(); navigate('/login'); };

  const [isFullscreen, setIsFullscreen] = useState(Boolean(document.fullscreenElement));

  useEffect(() => {
    const onChange = () => setIsFullscreen(Boolean(document.fullscreenElement));
    document.addEventListener('fullscreenchange', onChange);
    return () => document.removeEventListener('fullscreenchange', onChange);
  }, []);

  const handleFullscreenToggle = () => {
    if (document.fullscreenElement) {
      document.exitFullscreen();
    } else {
      document.documentElement.requestFullscreen();
    }
  };

  const handleChangePasswordOpen = () => {
    setAnchorEl(null);
    setCpCurrentPassword(''); setCpNewPassword(''); setCpConfirmPassword('');
    setCpError(''); setCpSuccess('');
    setChangePasswordOpen(true);
  };

  const handleChangePasswordSubmit = async () => {
    setCpError(''); setCpSuccess('');
    if (cpNewPassword !== cpConfirmPassword) { setCpError(t('common:passwords_not_match')); return; }
    if (cpNewPassword.length < 8) { setCpError(t('common:password_min')); return; }
    setCpLoading(true);
    try {
      await authService.changePassword(cpCurrentPassword, cpNewPassword);
      setCpSuccess(t('common:password_changed'));
      setTimeout(() => setChangePasswordOpen(false), 1500);
    } catch (err) {
      setCpError(err.response?.data?.message || t('common:failed_to_change_password'));
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
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Box
              component="img"
              src="/LumiPOS-logo.png"
              alt="BMS logo"
              sx={{ width: 50, height: 50, display: 'block' }}
            />
            <Typography variant="h6" noWrap component="div" sx={{ fontWeight: 'bold', color: 'primary.dark' }}>
              LumiPOS
            </Typography>
          </Box>
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
          <Box key={group.labelKey}>
            {!collapsed && (
              <Typography
                variant="caption"
                sx={{ display: 'block', px: 2, pt: 2, pb: 0.5, color: 'text.disabled', fontWeight: 'bold', letterSpacing: 0.5 }}
              >
                {t(group.labelKey).toUpperCase()}
              </Typography>
            )}
            <List dense>
              {visibleItems.map((item) => {
                const label = t(item.textKey);
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
                    {!collapsed && <ListItemText primary={label} />}
                  </ListItemButton>
                );
                return (
                  <ListItem key={item.textKey} disablePadding sx={{ display: 'block' }}>
                    {collapsed ? (
                      <Tooltip title={label} placement="right" arrow>
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
            {getPageTitle(location.pathname, t)}
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
              {shopName || t('common:shop_name_fallback')}
            </Typography>
            {currentShift && (
              <Chip
                icon={<CashIcon />}
                label={t('cash:opened_at', { time: new Date(currentShift.openingTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) })}
                color="success"
                size="small"
                variant="filled"
                sx={{ ml: 1 }}
              />
            )}
          </Box>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Tooltip title={t(isFullscreen ? 'exit_fullscreen' : 'fullscreen')}>
              <IconButton onClick={handleFullscreenToggle} size="small" color="inherit">
                {isFullscreen ? <FullscreenExitIcon /> : <FullscreenIcon />}
              </IconButton>
            </Tooltip>
            <LanguageSwitcher compact />
            {currentShift && (
              <Chip
                icon={<CashIcon />}
                label={t('cash:status_open')}
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
            <MenuItem onClick={handleChangePasswordOpen}><LockIcon sx={{ mr: 1 }} />{t('common:change_password')}</MenuItem>
            <MenuItem onClick={handleLogout}><Logout sx={{ mr: 1 }} />{t('common:logout')}</MenuItem>
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
          minWidth: 0,
        }}
      >
        {/* Per-route Suspense boundary: lazy pages load INSIDE the content
            area only — the drawer and app bar stay mounted, so navigating
            never flashes the whole window. */}
        <Suspense fallback={<ContentSkeleton />}>
          {children || <Outlet />}
        </Suspense>
      </Box>

      {lic?.licensed && lic.plan === 'trial' && lic.daysLeft <= 7 && (
      <Alert severity="info" sx={{ mb: 2 }}>
          ⏳ Your trial ends in <strong>{lic.daysLeft} days</strong>.
          Contact MegaCode to upgrade — your data stays safe.
      </Alert>
  )}

      <Dialog open={changePasswordOpen} onClose={() => setChangePasswordOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle>{t('common:change_password')}</DialogTitle>
        <DialogContent>
          {cpError && <Alert severity="error" sx={{ mb: 2 }}>{cpError}</Alert>}
          {cpSuccess && <Alert severity="success" sx={{ mb: 2 }}>{cpSuccess}</Alert>}
          <TextField fullWidth label={t('common:current_password')} type="password" value={cpCurrentPassword} onChange={(e) => setCpCurrentPassword(e.target.value)} sx={{ mb: 2, mt: 1 }} />
          <TextField fullWidth label={t('common:new_password')} type="password" value={cpNewPassword} onChange={(e) => setCpNewPassword(e.target.value)} sx={{ mb: 2 }} />
          <TextField fullWidth label={t('common:confirm_password')} type="password" value={cpConfirmPassword} onChange={(e) => setCpConfirmPassword(e.target.value)} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setChangePasswordOpen(false)}>{t('common:cancel')}</Button>
          <Button onClick={handleChangePasswordSubmit} variant="contained" disabled={cpLoading}>
            {cpLoading ? t('common:loading') : t('common:change_password')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default DashboardLayout;
