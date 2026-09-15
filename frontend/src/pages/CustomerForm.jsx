import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams, useLocation } from 'react-router-dom';
import { Box, Typography, TextField, Button, Grid, Paper, Alert, CircularProgress, IconButton } from '@mui/material';
import { Add as AddIcon, RemoveCircleOutline as RemoveIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { customerService } from '../api/services';
import { useAuth } from '../context/AuthContext';
import { preventNumberScroll } from '../utils/helpers';

const CustomerForm = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const { t } = useTranslation('customers');
  const isEdit = !!id;

  const [formData, setFormData] = useState({
      firstName: '',
      lastName: '',
      phones: [''],
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
          phones: (c.phones && c.phones.length) ? c.phones : (c.phone ? [c.phone] : ['']),
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
      setTimeout(() => navigate(`/customers${location.search}`), 1500);
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
        phone: formData.phones[0] || '',
        phones: formData.phones.map((p) => p.trim()).filter(Boolean),
        address: formData.address,
        city: formData.city,
        creditLimit: formData.creditLimit === '' ? null : parseFloat(formData.creditLimit),
    };

    saveMutation.mutate(customerRequest);
  };

  const addPhone = () => setFormData({ ...formData, phones: [...formData.phones, ''] });
  const removePhone = (index) => setFormData({
    ...formData,
    phones: formData.phones.length > 1 ? formData.phones.filter((_, i) => i !== index) : formData.phones,
  });

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
              {formData.phones.map((p, i) => (
                <Box key={i} sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
                  <TextField
                    fullWidth
                    label={i === 0 ? t('phone') : `${t('phone')} ${i + 1}`}
                    value={p}
                    onChange={(e) => {
                      const phones = [...formData.phones];
                      phones[i] = e.target.value;
                      setFormData({ ...formData, phones });
                    }}
                  />
                  {formData.phones.length > 1 && (
                    <IconButton size="small" color="error" onClick={() => removePhone(i)} aria-label={t('remove_phone')}>
                      <RemoveIcon />
                    </IconButton>
                  )}
                </Box>
              ))}
              <Button startIcon={<AddIcon />} size="small" onClick={addPhone}>{t('add_phone')}</Button>
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('email_or_account')} name="email" value={formData.email} onChange={(e) => setFormData({ ...formData, email: e.target.value })} />
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
                onWheel={preventNumberScroll}
                helperText={t('credit_limit_hint')}
              />
            </Grid>
            <Grid item xs={12}>
              <Button type="submit" variant="contained" disabled={saveMutation.isPending}>
                {saveMutation.isPending ? <CircularProgress size={24} /> : (isEdit ? t('update') : t('create'))}
              </Button>
              <Button onClick={() => navigate(`/customers${location.search}`)} sx={{ ml: 1 }}>{t('cancel')}</Button>
            </Grid>
          </Grid>
        </form>
      </Paper>
    </Box>
  );
};

export default CustomerForm;
