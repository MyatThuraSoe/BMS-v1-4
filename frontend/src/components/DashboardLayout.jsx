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
} from '@mui/icons-material';
import { useAuth } from '../context/AuthContext';

const drawerWidth = 240;

const menuItems = [
  { text: 'Dashboard', icon: <DashboardIcon />, path: '/dashboard', roles: ['ADMIN', 'MANAGER', 'CASHIER'] },
  { text: 'POS', icon: <PosIcon />, path: '/pos', roles: ['ADMIN', 'MANAGER', 'CASHIER'] },
  { text: 'Products', icon: <InventoryIcon />, path: '/products', roles: ['ADMIN', 'MANAGER'] },
  { text: 'Categories', icon: <InventoryIcon />, path: '/categories', roles: ['ADMIN', 'MANAGER'] },
  { text: 'Inventory', icon: <InventoryIcon />, path: '/inventory', roles: ['ADMIN', 'MANAGER'] },
  { text: 'Suppliers', icon: <SupplierIcon />, path: '/suppliers', roles: ['ADMIN'] },
  { text: 'Purchases', icon: <CartIcon />, path: '/purchases', roles: ['ADMIN', 'MANAGER'] },
  { text: 'Customers', icon: <PeopleIcon />, path: '/customers', roles: ['ADMIN', 'MANAGER'] },
  { text: 'Sales', icon: <ReceiptIcon />, path: '/sales', roles: ['ADMIN', 'MANAGER', 'CASHIER'] },
  { text: 'Reports', icon: <ReportIcon />, path: '/reports', roles: ['ADMIN', 'MANAGER'] },
  { text: 'Users', icon: <SecurityIcon />, path: '/users', roles: ['ADMIN'] },
  { text: 'Settings', icon: <SettingsIcon />, path: '/settings', roles: ['ADMIN'] },
  { text: 'Audit Logs', icon: <HistoryIcon />, path: '/audit-logs', roles: ['ADMIN'] },
];

const DashboardLayout = ({ children }) => {
  const [mobileOpen, setMobileOpen] = useState(false);
  const [anchorEl, setAnchorEl] = useState(null);
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout, isAdmin, isManager } = useAuth();

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
    </Box>
  );
};

export default DashboardLayout;
