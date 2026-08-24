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
} from '@mui/material';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { userService } from '../api/services';
import { useAuth } from '../context/AuthContext';

const UserForm = () => {
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
      if (isEdit) {
        return userService.update(id, data);
      }
      return userService.create(data);
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
    if (!isEdit && !formData.password) newErrors.password = t('password_required');
    if (!isEdit && formData.password && formData.password.length < 8) {
      newErrors.password = t('password_min_length');
    }
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
    if (isEdit && !submitData.password) {
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
        {isEdit ? t('edit_user') : t('add_user')}
      </Typography>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError('')}>
          {error}
        </Alert>
      )}

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
                disabled={isEdit}
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
                helperText={errors.password || (isEdit ? t('password_keep_current') : '')}
                required={!isEdit}
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
              {isEdit ? t('update') : t('create')}
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

export default UserForm;
