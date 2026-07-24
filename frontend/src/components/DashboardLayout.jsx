import { useState } from 'react';
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
  Badge,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
  Alert,
} from '@mui/material';
import {
  Dashboard as DashboardIcon,
  Inventory as InventoryIcon,
  ShoppingCart as CartIcon,
  PointOfSale as PosIcon,
  People as PeopleIcon,
  LocalGroceryStore as SupplierIcon,
  Receipt as ReceiptIcon,
  Assessment as ReportIcon,
  Settings as SettingsIcon,
  Security as SecurityIcon,
  History as HistoryIcon,
  Menu as MenuIcon,
  ChevronLeft as ChevronLeftIcon,
  ExpandMore as ExpandMoreIcon,
  AccountCircle,
  Logout,
  Lock as LockIcon,
  CloudUpload as CloudUploadIcon,
} from '@mui/icons-material';
import { useAuth } from '../context/AuthContext';
import { authService } from '../api/services';
import { useQuery } from '@tanstack/react-query';
import { shopInfoService } from '../api/services';

const drawerWidth = 240;



const menuItems = [
  { text: 'Dashboard', icon: <DashboardIcon />, path: '/dashboard', roles: ['ADMIN', 'MANAGER'] },
  { text: 'POS', icon: <PosIcon />, path: '/pos', roles: ['ADMIN', 'MANAGER', 'CASHIER'] },
  { text: 'Products', icon: <InventoryIcon />, path: '/products', roles: ['ADMIN', 'MANAGER'] },
  { text: 'Categories', icon: <InventoryIcon />, path: '/categories', roles: ['ADMIN', 'MANAGER'] },
  { text: 'Inventory', icon: <InventoryIcon />, path: '/inventory', roles: ['ADMIN', 'MANAGER'] },
  { text: 'Suppliers', icon: <SupplierIcon />, path: '/suppliers', roles: ['ADMIN'] },
  { text: 'Purchases', icon: <CartIcon />, path: '/purchases', roles: ['ADMIN', 'MANAGER'] },
  { text: 'Customers', icon: <PeopleIcon />, path: '/customers', roles: ['ADMIN', 'MANAGER'] },
  { text: 'Sales', icon: <ReceiptIcon />, path: '/sales', roles: ['ADMIN', 'MANAGER', 'CASHIER'] },
  { text: 'Reports', icon: <ReportIcon />, path: '/reports', roles: ['ADMIN', 'MANAGER'] },
  { text: 'Analytics', icon: <ReportIcon />, path: '/analytics', roles: ['ADMIN'] },
  { text: 'Accounting', icon: <ReportIcon />, path: '/accounting', roles: ['ADMIN'] },
  { text: 'Users', icon: <SecurityIcon />, path: '/users', roles: ['ADMIN'] },
  { text: 'Settings', icon: <SettingsIcon />, path: '/settings', roles: ['ADMIN'] },
  { text: 'Shop Info', icon: <SettingsIcon />, path: '/shop-info', roles: ['ADMIN'] },
  { text: 'Audit Logs', icon: <HistoryIcon />, path: '/audit-logs', roles: ['ADMIN'] },

  // Example snippet for your sidebar menu array:
{
  text: 'Backup Settings',
  path: '/settings/backup',
  icon: <CloudUploadIcon />,
  roles: ['ADMIN'] // Only show to admins
},
];

const DashboardLayout = ({ children }) => {
  const [mobileOpen, setMobileOpen] = useState(false);
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
  const { user, logout, isAdmin, isManager } = useAuth();

  const { data: shopInfoData } = useQuery({
    queryKey: ['shopInfo'],
    queryFn: () => shopInfoService.get(),
  });

  const shopName = shopInfoData?.data?.shopName;

  const handleDrawerToggle = () => {
    setMobileOpen(!mobileOpen);
  };

  const handleMenuOpen = (event) => {
    setAnchorEl(event.currentTarget);
  };

  const handleMenuClose = () => {
    setAnchorEl(null);
  };

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const handleChangePasswordOpen = () => {
    setAnchorEl(null);
    setCpCurrentPassword('');
    setCpNewPassword('');
    setCpConfirmPassword('');
    setCpError('');
    setCpSuccess('');
    setChangePasswordOpen(true);
  };

  const handleChangePasswordSubmit = async () => {
    setCpError('');
    setCpSuccess('');
    if (cpNewPassword !== cpConfirmPassword) {
      setCpError('New passwords do not match');
      return;
    }
    if (cpNewPassword.length < 6) {
      setCpError('New password must be at least 6 characters');
      return;
    }
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
    if (!user) return false;
    const userRoles = user.roles?.map(r => r.name || r) || [];
    return item.roles.some(role => userRoles.includes(role));
  };

  const drawer = (
    <Box>
      <Toolbar>
        <Typography variant="h6" noWrap component="div" sx={{ fontWeight: 'bold' }}>
          BMS v1
        </Typography>
      </Toolbar>
      <Divider />
      <List>
        {menuItems.filter(canAccessItem).map((item) => (
          <ListItem key={item.text} disablePadding>
            <ListItemButton
              component={Link}
              to={item.path}
              selected={location.pathname === item.path}
              sx={{
                '&.Mui-selected': {
                  backgroundColor: 'primary.light',
                  color: 'primary.contrastText',
                },
                '&.Mui-selected:hover': {
                  backgroundColor: 'primary.main',
                },
              }}
            >
              <ListItemIcon sx={{ color: location.pathname === item.path ? 'inherit' : 'text.secondary' }}>
                {item.icon}
              </ListItemIcon>
              <ListItemText primary={item.text} />
            </ListItemButton>
          </ListItem>
        ))}
      </List>
    </Box>
  );

  return (
    <Box sx={{ display: 'flex' }}>
      <CssBaseline />
      <AppBar
        position="fixed"
        sx={{
          width: { sm: `calc(100% - ${drawerWidth}px)` },
          ml: { sm: `${drawerWidth}px` },
        }}
      >
        <Toolbar>
          <IconButton
            color="inherit"
            edge="start"
            onClick={handleDrawerToggle}
            sx={{ mr: 2, display: { sm: 'none' } }}
          >
            <MenuIcon />
          </IconButton>
          <Typography variant="h6" noWrap component="div" sx={{ flexGrow: 1 }}>
            {menuItems.find(item => item.path === location.pathname)?.text || 'Dashboard'}
          </Typography>
          <Box
            sx={{
              flexGrow: 1,
              display: 'flex',
              justifyContent: 'right',
              marginRight: 3,
            }}
          >
            <Typography variant="h6" noWrap component="div" sx={{ fontSize: '14px' }}>
              {shopName || 'BMS'}
            </Typography>
          </Box>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
            <Typography variant="body2">{user?.username}</Typography>
            <IconButton onClick={handleMenuOpen} size="small">
              <Avatar sx={{ width: 32, height: 32, bgcolor: 'secondary.main' }}>
                {user?.username?.charAt(0).toUpperCase()}
              </Avatar>
            </IconButton>
          </Box>
          <Menu
            anchorEl={anchorEl}
            open={Boolean(anchorEl)}
            onClose={handleMenuClose}
          >
            <MenuItem disabled>
              <AccountCircle sx={{ mr: 1 }} />
              {user?.username}
            </MenuItem>
            <Divider />
            <MenuItem onClick={handleChangePasswordOpen}>
              <LockIcon sx={{ mr: 1 }} />
              Change Password
            </MenuItem>
            <MenuItem onClick={handleLogout}>
              <Logout sx={{ mr: 1 }} />
              Logout
            </MenuItem>
          </Menu>
        </Toolbar>
      </AppBar>
      <Box
        component="nav"
        sx={{ width: { sm: drawerWidth }, flexShrink: { sm: 0 } }}
      >
        <Drawer
          variant="temporary"
          open={mobileOpen}
          onClose={handleDrawerToggle}
          ModalProps={{ keepMounted: true }}
          sx={{
            display: { xs: 'block', sm: 'none' },
            '& .MuiDrawer-paper': { boxSizing: 'border-box', width: drawerWidth },
          }}
        >
          {drawer}
        </Drawer>
        <Drawer
          variant="permanent"
          sx={{
            display: { xs: 'none', sm: 'block' },
            '& .MuiDrawer-paper': { boxSizing: 'border-box', width: drawerWidth },
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
          p: 3,
          mt: 8,
          width: { sm: `calc(100% - ${drawerWidth}px)` },
        }}
      >
        {children || <Outlet />}
      </Box>

      <Dialog open={changePasswordOpen} onClose={() => setChangePasswordOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Change Password</DialogTitle>
        <DialogContent>
          {cpError && <Alert severity="error" sx={{ mb: 2 }}>{cpError}</Alert>}
          {cpSuccess && <Alert severity="success" sx={{ mb: 2 }}>{cpSuccess}</Alert>}
          <TextField
            fullWidth
            label="Current Password"
            type="password"
            value={cpCurrentPassword}
            onChange={(e) => setCpCurrentPassword(e.target.value)}
            sx={{ mb: 2, mt: 1 }}
          />
          <TextField
            fullWidth
            label="New Password"
            type="password"
            value={cpNewPassword}
            onChange={(e) => setCpNewPassword(e.target.value)}
            sx={{ mb: 2 }}
          />
          <TextField
            fullWidth
            label="Confirm New Password"
            type="password"
            value={cpConfirmPassword}
            onChange={(e) => setCpConfirmPassword(e.target.value)}
          />
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
