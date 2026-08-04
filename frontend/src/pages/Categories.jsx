import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, IconButton, TablePagination,
} from '@mui/material';
import { Add as AddIcon, Edit as EditIcon, Delete as DeleteIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { categoryService } from '../api/services';
import { formatDateTime } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';
import { useUndoableDelete } from '../hooks/useUndoableDelete.jsx';

const Categories = () => {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();

  const { data: categoriesData, isLoading } = useQuery({
    queryKey: ['categories', page, size],
    queryFn: () => categoryService.getAll(page, size),
  });

  const deleteMutation = useMutation({
    mutationFn: (id) => categoryService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['categories'] });
    },
  });

  // Wrap the mutation for the undo hook
  const wrappedDelete = {
    mutateAsync: async (id) => {
      await deleteMutation.mutateAsync(id);
      queryClient.invalidateQueries({ queryKey: ['categories'] });
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
      if (window.confirm(`Category "${category.name}" has ${category.productCount} products. Deleting it may affect these products. Are you sure?`)) {
        deleteMutation.mutate(category.id);
      }
      return;
    }
    
    // For cheap deletes, use the undo toast
    handleUndoableDelete(category.id, category.name);
  };

  const categories = categoriesData?.data?.content || [];
  const totalElements = categoriesData?.data?.page?.totalElements || 0;

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', mb: 3 }}>
        {isManager() && (
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/categories/new')}>
            Add Category
          </Button>
        )}
      </Box>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell sx={{ fontWeight: 'bold' }}>Name</TableCell>
              <TableCell sx={{ fontWeight: 'bold' }}>Description</TableCell>
              <TableCell sx={{ fontWeight: 'bold' }}>Created</TableCell>
              {isManager() && <TableCell align="right" sx={{ fontWeight: 'bold' }}>Actions</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={4} align="center">Loading...</TableCell></TableRow>
            ) : categories.length === 0 ? (
              <TableRow><TableCell colSpan={4} align="center">No categories found</TableCell></TableRow>
            ) : (
              categories.map((cat) => (
                <TableRow key={cat.id}>
                  <TableCell>{cat.name}</TableCell>
                  <TableCell>{cat.description || '-'}</TableCell>
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