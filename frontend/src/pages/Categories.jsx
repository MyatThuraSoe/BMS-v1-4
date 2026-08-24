import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Box, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, IconButton, TablePagination, Grid, Alert,
} from '@mui/material';
import {
  Add as AddIcon, Edit as EditIcon, Delete as DeleteIcon,
  Inventory2 as InventoryIcon, LowPriority as LowStockIcon, Block as OutOfStockIcon,
  AttachMoney as RevenueIcon, ShoppingCart as UnitsSoldIcon, Category as CategoryIcon,
} from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { categoryService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';
import { useUndoableDelete } from '../hooks/useUndoableDelete.jsx';

const StatCard = ({ icon, label, value, color }) => (
  <Paper
    elevation={0}
    sx={{
      p: 2,
      display: 'flex',
      alignItems: 'center',
      gap: 1.5,
      border: '1px solid',
      borderColor: 'divider',
      height: '100%',
    }}
  >
    <Box
      sx={{
        width: 40, height: 40, borderRadius: 2,
        bgcolor: `${color}.light`, color: `${color}.dark`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}
    >
      {icon}
    </Box>
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="caption" color="text.secondary" noWrap>{label}</Typography>
      <Typography variant="h6" fontWeight={700} noWrap>{value}</Typography>
    </Box>
  </Paper>
);

const Categories = () => {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const { t } = useTranslation('inventory');

  const { data: categoriesData, isLoading } = useQuery({
    queryKey: ['categories', page, size],
    queryFn: () => categoryService.getAll(page, size),
  });

  const { data: statsData, isLoading: statsLoading, isError: statsError } = useQuery({
    queryKey: ['categories-stats-summary'],
    queryFn: () => categoryService.getStatsSummary(),
  });

  const deleteMutation = useMutation({
    mutationFn: (id) => categoryService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['categories'] });
      queryClient.invalidateQueries({ queryKey: ['categories-stats-summary'] });
    },
  });

  // Wrap the mutation for the undo hook
  const wrappedDelete = {
    mutateAsync: async (id) => {
      await deleteMutation.mutateAsync(id);
      queryClient.invalidateQueries({ queryKey: ['categories'] });
      queryClient.invalidateQueries({ queryKey: ['categories-stats-summary'] });
    }
  };

  // Renamed to 'handleUndoableDelete' to avoid shadowing conflicts
  const { handleDelete: handleUndoableDelete } = useUndoableDelete(wrappedDelete, {
    delay: 5000,
    itemName: 'Category'
  });

  const handleDeleteClick = (category) => {
    // For expensive deletes (categories with products), keep confirmation
    if (category.productCount > 0) {
      if (window.confirm(t('delete_category_warning', { name: category.name, count: category.productCount }))) {
        deleteMutation.mutate(category.id);
      }
      return;
    }
    
    // For cheap deletes, use the undo toast
    handleUndoableDelete(category.id, category.name);
  };

  const categories = categoriesData?.data?.content || [];
  const totalElements = categoriesData?.data?.page?.totalElements || 0;
  const stats = statsData?.data || {};

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3, flexWrap: 'wrap', gap: 2 }}>
        <Typography variant="h5" sx={{ color: 'primary.main' }}>
          {t('categories')}
        </Typography>
        {isManager() && (
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/categories/new')}>
            {t('add_category')}
          </Button>
        )}
      </Box>

      {statsError && (
        <Alert severity="warning" sx={{ mb: 2 }}>{t('failed_to_load_stats')}</Alert>
      )}

      <Grid container spacing={2} sx={{ mb: 3 }}>
        <Grid item xs={6} sm={4} md={2}>
          <StatCard icon={<CategoryIcon />} label={t('total_categories')} value={statsLoading ? '-' : stats.totalCategories ?? 0} color="primary" />
        </Grid>
        <Grid item xs={6} sm={4} md={2}>
          <StatCard icon={<InventoryIcon />} label={t('total_products')} value={statsLoading ? '-' : stats.totalProducts ?? 0} color="success" />
        </Grid>
        <Grid item xs={6} sm={4} md={2}>
          <StatCard icon={<LowStockIcon />} label={t('low_stock')} value={statsLoading ? '-' : stats.lowStockProducts ?? 0} color="warning" />
        </Grid>
        <Grid item xs={6} sm={4} md={2}>
          <StatCard icon={<OutOfStockIcon />} label={t('out_of_stock')} value={statsLoading ? '-' : stats.outOfStockProducts ?? 0} color="error" />
        </Grid>
        <Grid item xs={6} sm={4} md={2}>
          <StatCard icon={<RevenueIcon />} label={t('inventory_value')} value={statsLoading ? '-' : formatCurrency(stats.totalStockValue)} color="info" />
        </Grid>
        <Grid item xs={6} sm={4} md={2}>
          <StatCard icon={<UnitsSoldIcon />} label={t('units_sold')} value={statsLoading ? '-' : stats.unitsSold ?? 0} color="secondary" />
        </Grid>
      </Grid>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell sx={{ fontWeight: 'bold' }}>{t('name')}</TableCell>
              <TableCell sx={{ fontWeight: 'bold' }}>{t('description')}</TableCell>
              <TableCell align="center" sx={{ fontWeight: 'bold' }}>{t('product_count')}</TableCell>
              <TableCell align="center" sx={{ fontWeight: 'bold' }}>{t('low_stock')}</TableCell>
              <TableCell align="center" sx={{ fontWeight: 'bold' }}>{t('out_of_stock')}</TableCell>
              <TableCell align="right" sx={{ fontWeight: 'bold' }}>{t('stock_value')}</TableCell>
              <TableCell align="right" sx={{ fontWeight: 'bold' }}>{t('units_sold')}</TableCell>
              <TableCell align="right" sx={{ fontWeight: 'bold' }}>{t('revenue')}</TableCell>
              <TableCell sx={{ fontWeight: 'bold' }}>{t('created')}</TableCell>
              {isManager() && <TableCell align="right" sx={{ fontWeight: 'bold' }}>{t('actions')}</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={10} align="center">{t('loading')}</TableCell></TableRow>
            ) : categories.length === 0 ? (
              <TableRow><TableCell colSpan={10} align="center">{t('no_categories_found')}</TableCell></TableRow>
            ) : (
              categories.map((cat) => (
                <TableRow key={cat.id}>
                  <TableCell>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                      <Typography fontWeight={600}>{cat.name}</Typography>
                      {(cat.lowStockCount > 0 || cat.outOfStockCount > 0) && (
                        <Typography variant="caption" color={cat.outOfStockCount > 0 ? 'error.main' : 'warning.main'}>
                          !
                        </Typography>
                      )}
                    </Box>
                  </TableCell>
                  <TableCell sx={{ maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {cat.description || '-'}
                  </TableCell>
                  <TableCell align="center">{cat.productCount}</TableCell>
                  <TableCell align="center">
                    {cat.lowStockCount > 0 ? (
                      <Typography color="warning.main" fontWeight={600}>{cat.lowStockCount}</Typography>
                    ) : 0}
                  </TableCell>
                  <TableCell align="center">
                    {cat.outOfStockCount > 0 ? (
                      <Typography color="error.main" fontWeight={600}>{cat.outOfStockCount}</Typography>
                    ) : 0}
                  </TableCell>
                  <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace', fontSize: '0.85rem' }}>
                    {formatCurrency(cat.totalStockValue)}
                  </TableCell>
                  <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace', fontSize: '0.85rem' }}>
                    {cat.unitsSold}
                  </TableCell>
                  <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace', fontSize: '0.85rem', fontWeight: 600 }}>
                    {formatCurrency(cat.revenue)}
                  </TableCell>
                  <TableCell>{formatDateTime(cat.createdAt)}</TableCell>
                  {isManager() && (
                    <TableCell align="right">
                      <IconButton size="small" onClick={() => navigate(`/categories/${cat.id}`)}>
                        <EditIcon />
                      </IconButton>
                      <IconButton 
                        size="small" 
                        color="error" 
                        onClick={() => handleDeleteClick(cat)}
                      >
                        <DeleteIcon />
                      </IconButton>
                    </TableCell>
                  )}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
        <TablePagination 
          component="div" 
          count={totalElements} 
          page={page} 
          rowsPerPage={size} 
          onPageChange={(e, newPage) => setPage(newPage)} 
          onRowsPerPageChange={(e) => { setSize(parseInt(e.target.value, 10)); setPage(0); }} 
          rowsPerPageOptions={[5, 10, 25]} 
        />
      </TableContainer>
    </Box>
  );
};

export default Categories;
