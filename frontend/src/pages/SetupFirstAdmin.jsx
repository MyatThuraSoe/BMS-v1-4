import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import {
  Container,
  Box,
  Paper,
  Typography,
  TextField,
  Button,
  Alert,
  CircularProgress,
} from '@mui/material';
import { authService } from '../api/services';

const SetupFirstAdmin = () => {
  const [form, setForm] = useState({
    username: '',
    email: '',
    password: '',
    confirmPassword: '',
    firstName: '',
    lastName: '',
  });
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const { t } = useTranslation('auth');

  const setField = (key) => (e) => setForm((p) => ({ ...p, [key]: e.target.value }));

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setSuccess('');

    if (form.password !== form.confirmPassword) {
      setError(t('passwords_not_match'));
      return;
    }

    setLoading(true);
    try {
      await authService.registerFirstAdmin({
        username: form.username,
        email: form.email,
        password: form.password,
        firstName: form.firstName,
        lastName: form.lastName,
      });
      setSuccess(t('first_admin_registered'));
      setTimeout(() => navigate('/login'), 2000);
    } catch (err) {
      setError(err?.response?.data?.message || err?.friendlyMessage || t('failed_to_register_admin'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        bgcolor: 'background.default',
        backgroundImage: 'radial-gradient(circle at 1px 1px, rgba(28,38,32,0.06) 1px, transparent 0)',
        backgroundSize: '24px 24px',
      }}
    >
      <Container maxWidth="xs">
        <Paper
          elevation={0}
          sx={{
            p: 4,
            width: '100%',
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 3,
          }}
        >
          <Box sx={{ mb: 4, textAlign: 'center' }}>
            <Box
              sx={{
                width: 56, height: 56, borderRadius: 2, bgcolor: 'primary.main',
                color: 'white', display: 'flex', alignItems: 'center', justifyContent: 'center',
                mx: 'auto', mb: 2, fontFamily: '"Fraunces", serif', fontSize: '1.75rem', fontWeight: 700,
              }}
            >
              S
            </Box>
            <Typography variant="h4" component="h1" sx={{ fontWeight: 600 }}>
              {t('setup_title')}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              {t('setup_subtitle')}
            </Typography>
          </Box>

          {error && (
            <Alert severity="error" sx={{ width: '100%', mb: 2, borderRadius: 2 }}>
              {error}
            </Alert>
          )}

          {success && (
            <Alert severity="success" sx={{ width: '100%', mb: 2, borderRadius: 2 }}>
              {success}
            </Alert>
          )}

          <Box component="form" onSubmit={handleSubmit} sx={{ width: '100%' }}>
            <TextField
              fullWidth
              label={t('first_name')}
              value={form.firstName}
              onChange={setField('firstName')}
              margin="normal"
              required
              autoFocus
              disabled={loading}
              InputProps={{ sx: { borderRadius: 2 } }}
            />
            <TextField
              fullWidth
              label={t('last_name')}
              value={form.lastName}
              onChange={setField('lastName')}
              margin="normal"
              required
              disabled={loading}
              InputProps={{ sx: { borderRadius: 2 } }}
            />
            <TextField
              fullWidth
              label={t('username')}
              value={form.username}
              onChange={setField('username')}
              margin="normal"
              required
              autoComplete="username"
              disabled={loading}
              InputProps={{ sx: { borderRadius: 2 } }}
            />
            <TextField
              fullWidth
              label={t('email')}
              type="email"
              value={form.email}
              onChange={setField('email')}
              margin="normal"
              required
              autoComplete="email"
              disabled={loading}
              InputProps={{ sx: { borderRadius: 2 } }}
            />
            <TextField
              fullWidth
              label={t('password')}
              type="password"
              value={form.password}
              onChange={setField('password')}
              margin="normal"
              required
              autoComplete="new-password"
              disabled={loading}
              InputProps={{ sx: { borderRadius: 2 } }}
            />
            <TextField
              fullWidth
              label={t('confirm_password')}
              type="password"
              value={form.confirmPassword}
              onChange={setField('confirmPassword')}
              margin="normal"
              required
              autoComplete="new-password"
              disabled={loading}
              InputProps={{ sx: { borderRadius: 2 } }}
            />
            <Button
              type="submit"
              fullWidth
              variant="contained"
              size="large"
              sx={{ mt: 3, py: 1.5 }}
              disabled={loading}
            >
              {loading ? <CircularProgress size={24} sx={{ color: 'white' }} /> : t('create_admin_account')}
            </Button>
          </Box>
        </Paper>
      </Container>
    </Box>
  );
};

export default SetupFirstAdmin;
