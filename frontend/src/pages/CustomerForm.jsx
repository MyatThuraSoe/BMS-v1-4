import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router-dom';
import { Box, Typography, TextField, Button, Grid, Paper, Alert, CircularProgress } from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { customerService } from '../api/services';
import { useAuth } from '../context/AuthContext';

const CustomerForm = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const { t } = useTranslation('customers');
  const isEdit = !!id;

  const [formData, setFormData] = useState({
      firstName: '',
      lastName: '',
      phone: '',
      email: '',
      address: '',
      city: '',
      creditLimit: '',
  });
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const { data: existingCustomer } = useQuery({ queryKey: ['customer', id], queryFn: () => customerService.getById(id), enabled: isEdit });

  useEffect(() => {
    if (existingCustomer?.data) {
      const c = existingCustomer.data;

      setFormData({
          firstName: c.firstName || '',
          lastName: c.lastName || '',
          phone: c.phone || '',
          email: c.email || '',
          address: c.address || '',
          city: c.city || '',
          creditLimit: c.creditLimit != null ? String(c.creditLimit) : '',
      });
    }
  }, [existingCustomer]);

  const saveMutation = useMutation({
    mutationFn: async (data) => {
      if (isEdit) return customerService.update(id, data);
      return customerService.create(data);
    },
    onSuccess: () => {
      setSuccess(isEdit ? t('customer_updated') : t('customer_created'));
      queryClient.invalidateQueries({ queryKey: ['customers'] });
      setTimeout(() => navigate('/customers'), 1500);
    },
    onError: (err) => {
      if (err.response?.status === 409) {
        setError(t('conflict_error'));
      } else {
        setError(err.response?.data?.message || t('failed_to_save'));
      }
    },
  });

  const handleSubmit = (e) => {
    e.preventDefault();
    setError('');
    setSuccess('');

    const customerRequest = {
        firstName: formData.firstName,
        lastName: formData.lastName,
        email: formData.email,
        phone: formData.phone,
        address: formData.address,
        city: formData.city,
        creditLimit: formData.creditLimit === '' ? null : parseFloat(formData.creditLimit),
    };

    saveMutation.mutate(customerRequest);
  };

  if (!isManager()) return <Alert severity="error">{t('access_denied')}</Alert>;

  return (
    <Box>
      <Typography variant="h4" gutterBottom>{isEdit ? t('edit_customer') : t('add_customer')}</Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }}>{success}</Alert>}
      <Paper sx={{ p: 3 }}>
        <form onSubmit={handleSubmit}>
          <Grid container spacing={2}>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('firstname')} name="firstName" value={formData.firstName} onChange={(e) => setFormData({ ...formData, firstName: e.target.value })} required />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('lastname')} name="lastName" value={formData.lastName} onChange={(e) => setFormData({ ...formData, lastName: e.target.value })} required />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('phone')} name="phone" value={formData.phone} onChange={(e) => setFormData({ ...formData, phone: e.target.value })} />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('email')} name="email" type="email" value={formData.email} onChange={(e) => setFormData({ ...formData, email: e.target.value })} />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('address')} name="address" value={formData.address} onChange={(e) => setFormData({ ...formData, address: e.target.value })} />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('city')} name="city" value={formData.city} onChange={(e) => setFormData({ ...formData, city: e.target.value })} />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField
                fullWidth
                label={t('credit_limit')}
                name="creditLimit"
                type="number"
                value={formData.creditLimit}
                onChange={(e) => setFormData({ ...formData, creditLimit: e.target.value })}
                helperText={t('credit_limit_hint')}
              />
            </Grid>
            <Grid item xs={12}>
              <Button type="submit" variant="contained" disabled={saveMutation.isPending}>
                {saveMutation.isPending ? <CircularProgress size={24} /> : (isEdit ? t('update') : t('create'))}
              </Button>
              <Button onClick={() => navigate('/customers')} sx={{ ml: 1 }}>{t('cancel')}</Button>
            </Grid>
          </Grid>
        </form>
      </Paper>
    </Box>
  );
};

export default CustomerForm;
