import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Box, Typography, TextField, Button, Grid, Paper, Alert, CircularProgress, IconButton } from '@mui/material';
import { Add as AddIcon, RemoveCircleOutline as RemoveIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { supplierService } from '../api/services';
import { useAuth } from '../context/AuthContext';

const SupplierForm = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const { t } = useTranslation('purchases');
  const isEdit = !!id;

  const [formData, setFormData] = useState({ name: '', contactPerson: '', phones: [''], email: '', address: '' });
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const { data: existingSupplier } = useQuery({
    queryKey: ['supplier', id],
    queryFn: () => supplierService.getById(id),
    enabled: isEdit,
  });

  useEffect(() => {
    if (existingSupplier?.data) {
      const s = existingSupplier.data;
      setFormData({ name: s.name || '', contactPerson: s.contactPerson || '', phones: (s.phones && s.phones.length) ? s.phones : (s.phone ? [s.phone] : ['']), email: s.email || '', address: s.address || '' });
    }
  }, [existingSupplier]);

  const saveMutation = useMutation({
    mutationFn: async (data) => {
      if (isEdit) return supplierService.update(id, data);
      return supplierService.create(data);
    },
    onSuccess: () => {
      setSuccess(isEdit ? t('supplier_updated') : t('supplier_created'));
      queryClient.invalidateQueries({ queryKey: ['suppliers'] });
      setTimeout(() => navigate('/suppliers'), 1500);
    },
    onError: (err) => {
      if (err.response?.status === 409) {
        setError(t('supplier_conflict_409'));
      } else {
        setError(err.response?.data?.message || t('save_failed'));
      }
    },
  });

  const handleSubmit = (e) => {
    e.preventDefault();
    setError('');
    setSuccess('');
    saveMutation.mutate({
      ...formData,
      phone: formData.phones[0] || '',
      phones: formData.phones.map((p) => p.trim()).filter(Boolean),
    });
  };

  const addPhone = () => setFormData({ ...formData, phones: [...formData.phones, ''] });
  const removePhone = (index) => setFormData({
    ...formData,
    phones: formData.phones.length > 1 ? formData.phones.filter((_, i) => i !== index) : formData.phones,
  });

  if (!isManager()) return <Alert severity="error">{t('access_denied')}</Alert>;

  return (
    <Box>
      <Typography variant="h4" gutterBottom>{isEdit ? t('edit_supplier') : t('add_supplier')}</Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }}>{success}</Alert>}
      <Paper sx={{ p: 3 }}>
        <form onSubmit={handleSubmit}>
          <Grid container spacing={2}>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('name')} name="name" value={formData.name} onChange={(e) => setFormData({ ...formData, name: e.target.value })} required />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('contact_person')} name="contactPerson" value={formData.contactPerson} onChange={(e) => setFormData({ ...formData, contactPerson: e.target.value })} />
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
            <Grid item xs={12}>
              <TextField fullWidth label={t('address')} name="address" multiline rows={2} value={formData.address} onChange={(e) => setFormData({ ...formData, address: e.target.value })} />
            </Grid>
            <Grid item xs={12}>
              <Button type="submit" variant="contained" disabled={saveMutation.isPending}>
                {saveMutation.isPending ? <CircularProgress size={24} /> : (isEdit ? t('update') : t('create'))}
              </Button>
              <Button onClick={() => navigate('/suppliers')} sx={{ ml: 1 }}>{t('cancel')}</Button>
            </Grid>
          </Grid>
        </form>
      </Paper>
    </Box>
  );
};

export default SupplierForm;
