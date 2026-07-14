import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Box, Typography, TextField, Button, Grid, Paper, Alert, CircularProgress } from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { supplierService } from '../api/services';
import { useAuth } from '../context/AuthContext';

const SupplierForm = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const isEdit = !!id;

  const [formData, setFormData] = useState({ name: '', contactPerson: '', phone: '', email: '', address: '' });
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
      setFormData({ name: s.name || '', contactPerson: s.contactPerson || '', phone: s.phone || '', email: s.email || '', address: s.address || '' });
    }
  }, [existingSupplier]);

  const saveMutation = useMutation({
    mutationFn: async (data) => {
      if (isEdit) return supplierService.update(id, data);
      return supplierService.create(data);
    },
    onSuccess: () => {
      setSuccess(isEdit ? 'Supplier updated' : 'Supplier created');
      queryClient.invalidateQueries(['suppliers']);
      setTimeout(() => navigate('/suppliers'), 1500);
    },
    onError: (err) => setError(err.response?.data?.message || 'Failed to save'),
  });

  const handleSubmit = (e) => {
    e.preventDefault();
    setError('');
    setSuccess('');
    saveMutation.mutate(formData);
  };

  if (!isManager()) return <Alert severity="error">Access denied</Alert>;

  return (
    <Box>
      <Typography variant="h4" gutterBottom>{isEdit ? 'Edit Supplier' : 'Add Supplier'}</Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }}>{success}</Alert>}
      <Paper sx={{ p: 3 }}>
        <form onSubmit={handleSubmit}>
          <Grid container spacing={2}>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Name" name="name" value={formData.name} onChange={(e) => setFormData({ ...formData, name: e.target.value })} required />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Contact Person" name="contactPerson" value={formData.contactPerson} onChange={(e) => setFormData({ ...formData, contactPerson: e.target.value })} />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Phone" name="phone" value={formData.phone} onChange={(e) => setFormData({ ...formData, phone: e.target.value })} />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Email" name="email" type="email" value={formData.email} onChange={(e) => setFormData({ ...formData, email: e.target.value })} />
            </Grid>
            <Grid item xs={12}>
              <TextField fullWidth label="Address" name="address" multiline rows={2} value={formData.address} onChange={(e) => setFormData({ ...formData, address: e.target.value })} />
            </Grid>
            <Grid item xs={12}>
              <Button type="submit" variant="contained" disabled={saveMutation.isPending}>
                {saveMutation.isPending ? <CircularProgress size={24} /> : (isEdit ? 'Update' : 'Create')}
              </Button>
              <Button onClick={() => navigate('/suppliers')} sx={{ ml: 1 }}>Cancel</Button>
            </Grid>
          </Grid>
        </form>
      </Paper>
    </Box>
  );
};

export default SupplierForm;
