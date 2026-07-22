import { useState, useEffect } from 'react';
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
  const isEdit = !!id;

  const [formData, setFormData] = useState({
      firstName: '',
      lastName: '',
      phone: '',
      email: '',
      address: '',
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
      });
    }
  }, [existingCustomer]);

  const saveMutation = useMutation({
    mutationFn: async (data) => {
      if (isEdit) return customerService.update(id, data);
      return customerService.create(data);
    },
    onSuccess: () => {
      setSuccess(isEdit ? 'Customer updated' : 'Customer created');
      queryClient.invalidateQueries(['customers']);
      setTimeout(() => navigate('/customers'), 1500);
    },
    onError: (err) => setError(err.response?.data?.message || 'Failed to save'),
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
    };

    saveMutation.mutate(customerRequest);
  };

  if (!isManager()) return <Alert severity="error">Access denied</Alert>;

  return (
    <Box>
      <Typography variant="h4" gutterBottom>{isEdit ? 'Edit Customer' : 'Add Customer'}</Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }}>{success}</Alert>}
      <Paper sx={{ p: 3 }}>
        <form onSubmit={handleSubmit}>
          <Grid container spacing={2}>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Firstname" name="firstName" value={formData.firstName} onChange={(e) => setFormData({ ...formData, firstName: e.target.value })} required />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Lastname" name="lastName" value={formData.lastName} onChange={(e) => setFormData({ ...formData, lastName: e.target.value })} required />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Phone" name="phone" value={formData.phone} onChange={(e) => setFormData({ ...formData, phone: e.target.value })} />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Email" name="email" type="email" value={formData.email} onChange={(e) => setFormData({ ...formData, email: e.target.value })} />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Address" name="address" value={formData.address} onChange={(e) => setFormData({ ...formData, address: e.target.value })} />
            </Grid>
            <Grid item xs={12}>
              <Button type="submit" variant="contained" disabled={saveMutation.isPending}>
                {saveMutation.isPending ? <CircularProgress size={24} /> : (isEdit ? 'Update' : 'Create')}
              </Button>
              <Button onClick={() => navigate('/customers')} sx={{ ml: 1 }}>Cancel</Button>
            </Grid>
          </Grid>
        </form>
      </Paper>
    </Box>
  );
};

export default CustomerForm;
