import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Box,
  Typography,
  TextField,
  Button,
  Grid,
  Paper,
  MenuItem,
  Alert,
  FormControl,
  InputLabel,
  Select,
  Avatar,
  Stack,
} from '@mui/material';
import {
  ShoppingCart as CartIcon,
  Payments as PaymentsIcon,
  Today as TodayIcon,
} from '@mui/icons-material';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { userService } from '../api/services';
import { useAuth } from '../context/AuthContext';
import { formatCurrency } from '../utils/helpers';

const StatCard = ({ label, value, icon, highlight, highlightColor = 'success' }) => (
  <Paper
    elevation={0}
    sx={{
      p: 2.5,
      borderRadius: 2,
      bgcolor: highlight ? `${highlightColor}.50` : 'background.paper',
      border: '1px solid',
      borderColor: highlight ? `${highlightColor}.200` : 'divider',
      height: '100%',
      display: 'flex',
      flexDirection: 'column',
      justifyContent: 'space-between',
      transition: 'transform 0.2s',
      '&:hover': { transform: 'translateY(-2px)' }
    }}
  >
    <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mb: 1.5 }}>
      {icon && (
        <Avatar sx={{ bgcolor: `${highlightColor}.100`, color: `${highlightColor}.main`, width: 32, height: 32 }}>
          {icon}
        </Avatar>
      )}
      <Typography variant="caption" color="text.secondary" fontWeight={600} sx={{ textTransform: 'uppercase', letterSpacing: '0.5px' }}>
        {label}
      </Typography>
    </Stack>
    <Typography
      variant="h5"
      fontWeight={700}
      color="text.primary"
      sx={{ fontFamily: '"IBM Plex Mono", monospace', letterSpacing: '-0.5px' }}
    >
      {value}
    </Typography>
  </Paper>
);

const UserUpdate = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const { user: currentUser } = useAuth();
  const isEdit = !!id;
  const queryClient = useQueryClient();
  const { t } = useTranslation('users');

  const [formData, setFormData] = useState({
    username: '',
    email: '',
    password: '',
    firstName: '',
    lastName: '',
    phone: '',
    roleId: '',
    active: true,
  });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState('');

  // Fetch user data if editing
  const { data: userData } = useQuery({
    queryKey: ['user', id],
    queryFn: () => userService.getById(id),
    enabled: isEdit,
  });

  const { data: statsData, isLoading: statsLoading } = useQuery({
    queryKey: ['user-stats', id],
    queryFn: () => userService.getStats(id),
    enabled: isEdit,
  });
  const stats = statsData?.data;

  useEffect(() => {
    if (userData?.data) {
      const user = userData.data;
      setFormData({
        username: user.username || '',
        email: user.email || '',
        password: '',
        firstName: user.firstName || '',
        lastName: user.lastName || '',
        phone: user.phone || '',
        roleId:
            user.roleName === 'ROLE_ADMIN'
              ? 1
              : user.roleName === 'ROLE_MANAGER'
              ? 2
              : user.roleName === 'ROLE_CASHIER'
              ? 3
              : '',
        active: user.isActive !== false,
      });
    }
  }, [userData]);

  const mutation = useMutation({
    mutationFn: (data) => {
      return userService.update(id, data);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] });
      navigate('/users');
    },
    onError: (err) => {
      setError(err.response?.data?.message || t('failed_to_save_user'));
    },
  });

  const validate = () => {
    const newErrors = {};
    if (!formData.username.trim()) newErrors.username = t('username_required');
    if (!formData.email.trim()) newErrors.email = t('email_required');
    else if (!/\S+@\S+\.\S+/.test(formData.email)) newErrors.email = t('email_invalid');
    if (!formData.firstName.trim()) newErrors.firstName = t('first_name_required');
    if (!formData.lastName.trim()) newErrors.lastName = t('last_name_required');
    if (!formData.roleId) newErrors.roleId = t('role_required');
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    setError('');
    if (!validate()) return;

    const submitData = { ...formData };
    if (!submitData.password) {
      delete submitData.password;
    }
    submitData.roleId = parseInt(submitData.roleId, 10);
    submitData.isActive = formData.active;
    delete submitData.active;

    mutation.mutate(submitData);
  };

  const handleChange = (e) => {
    const { name, value, type, checked } = e.target;
    setFormData((prev) => ({
      ...prev,
      [name]: type === 'checkbox' ? checked : value,
    }));
    if (errors[name]) {
      setErrors((prev) => ({ ...prev, [name]: '' }));
    }
  };

  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        {t('edit_user')}
      </Typography>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError('')}>
          {error}
        </Alert>
      )}

      <Paper sx={{ p: 3, mb: 3, maxWidth: 800 }}>
        <Typography variant="h6" fontWeight={700} sx={{ mb: 2.5, display: 'flex', alignItems: 'center', gap: 1 }}>
          <PaymentsIcon color="success" /> {t('sales_performance')}
        </Typography>
        <Grid container spacing={2.5}>
          <Grid item xs={6} md={3}>
            <StatCard label={t('sales_performance_total_sales')} value={statsLoading || !stats ? '—' : stats.totalSales} icon={<CartIcon />} />
          </Grid>
          <Grid item xs={6} md={3}>
            <StatCard label={t('sales_performance_total_revenue')} value={statsLoading || !stats ? '—' : formatCurrency(stats.totalRevenue)} icon={<PaymentsIcon />} />
          </Grid>
          <Grid item xs={6} md={3}>
            <StatCard label={t('sales_performance_today_sales')} value={statsLoading || !stats ? '—' : stats.todaySales} icon={<TodayIcon />} />
          </Grid>
          <Grid item xs={6} md={3}>
            <StatCard label={t('sales_performance_today_revenue')} value={statsLoading || !stats ? '—' : formatCurrency(stats.todayRevenue)} icon={<TodayIcon />} highlight />
          </Grid>
        </Grid>
      </Paper>

      <Paper sx={{ p: 3, maxWidth: 800 }}>
        <form onSubmit={handleSubmit}>
          <Grid container spacing={2}>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                label={t('username')}
                name="username"
                value={formData.username}
                onChange={handleChange}
                error={!!errors.username}
                helperText={errors.username}
                required
                disabled
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                label={t('email')}
                name="email"
                type="email"
                value={formData.email}
                onChange={handleChange}
                error={!!errors.email}
                helperText={errors.email}
                required
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                label={t('password')}
                name="password"
                type="password"
                value={formData.password}
                onChange={handleChange}
                error={!!errors.password}
                helperText={errors.password || t('password_keep_current')}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <FormControl fullWidth error={!!errors.roleId}>
                <InputLabel required>{t('role')}</InputLabel>
                <Select
                  name="roleId"
                  value={formData.roleId}
                  onChange={handleChange}
                  label={t('role')}
                >
                  <MenuItem value={1}>{t('role_admin')}</MenuItem>
                  <MenuItem value={2}>{t('role_manager')}</MenuItem>
                  <MenuItem value={3}>{t('role_cashier')}</MenuItem>
                </Select>
                {errors.roleId && <Typography color="error">{errors.roleId}</Typography>}
              </FormControl>
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                label={t('first_name')}
                name="firstName"
                value={formData.firstName}
                onChange={handleChange}
                error={!!errors.firstName}
                helperText={errors.firstName}
                required
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                label={t('last_name')}
                name="lastName"
                value={formData.lastName}
                onChange={handleChange}
                error={!!errors.lastName}
                helperText={errors.lastName}
                required
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                label={t('phone')}
                name="phone"
                value={formData.phone}
                onChange={handleChange}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <FormControl fullWidth>
                <InputLabel>{t('status')}</InputLabel>
                <Select
                  name="active"
                  value={formData.active ? 'true' : 'false'}
                  onChange={(e) =>
                    setFormData((prev) => ({ ...prev, active: e.target.value === 'true' }))
                  }
                  label={t('status')}
                >
                  <MenuItem value="true">{t('active')}</MenuItem>
                  <MenuItem value="false">{t('inactive')}</MenuItem>
                </Select>
              </FormControl>
            </Grid>
          </Grid>

          <Box sx={{ mt: 3, display: 'flex', gap: 2 }}>
            <Button type="submit" variant="contained" disabled={mutation.isPending}>
              {t('update')}
            </Button>
            <Button
              type="button"
              variant="outlined"
              onClick={() => navigate('/users')}
            >
              {t('cancel')}
            </Button>
          </Box>
        </form>
      </Paper>
    </Box>
  );
};

export default UserUpdate;
