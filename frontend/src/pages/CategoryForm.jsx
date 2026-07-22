import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Box, Typography, TextField, Button, Grid, Paper, Alert, CircularProgress } from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { categoryService } from '../api/services';
import { useAuth } from '../context/AuthContext';

const CategoryForm = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const isEdit = !!id;

  const [formData, setFormData] = useState({ name: '', description: '' });
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const { data: existingCategory } = useQuery({
    queryKey: ['category', id],
    queryFn: () => categoryService.getById(id),
    enabled: isEdit,
  });

  useEffect(() => {
    if (existingCategory?.data) {
      const c = existingCategory.data;
      setFormData({ name: c.name || '', description: c.description || '' });
    }
  }, [existingCategory]);

  const saveMutation = useMutation({
    mutationFn: async (data) => {
      if (isEdit) return categoryService.update(id, data);
      return categoryService.create(data);
    },
    onSuccess: () => {
      setSuccess(isEdit ? 'Category updated' : 'Category created');
      queryClient.invalidateQueries(['categories']);
      setTimeout(() => navigate('/categories'), 1500);
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
      <Typography variant="h4" gutterBottom>{isEdit ? 'Edit Category' : 'Add Category'}</Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }}>{success}</Alert>}
      <Paper sx={{ p: 3 }}>
        <form onSubmit={handleSubmit}>
          <Grid container spacing={2}>
            <Grid item xs={12}>
              <TextField fullWidth label="Name" name="name" value={formData.name} onChange={(e) => setFormData({ ...formData, name: e.target.value })} required />
            </Grid>
            <Grid item xs={12}>
              <TextField fullWidth label="Description" name="description" multiline rows={3} value={formData.description} onChange={(e) => setFormData({ ...formData, description: e.target.value })} />
            </Grid>
            <Grid item xs={12}>
              <Button type="submit" variant="contained" disabled={saveMutation.isPending}>
                {saveMutation.isPending ? <CircularProgress size={24} /> : (isEdit ? 'Update' : 'Create')}
              </Button>
              <Button onClick={() => navigate('/categories')} sx={{ ml: 1 }}>Cancel</Button>
            </Grid>
          </Grid>
        </form>
      </Paper>
    </Box>
  );
};

export default CategoryForm;
