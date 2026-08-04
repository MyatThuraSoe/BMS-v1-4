// src/hooks/useCategories.js (or wherever your category hooks are)
import { useMutation, useQueryClient } from '@tanstack/react-query';
import api from '../api/axios';
import { useUndoableDelete } from './useUndoableDelete';

export function useDeleteCategory() {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: async (id) => {
      await api.delete(`/categories/${id}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['categories'] });
      queryClient.invalidateQueries({ queryKey: ['products'] }); // In case category counts change
    }
  });
}

// In your Categories.jsx page
import { useDeleteCategory } from '../hooks/useCategories';
import { useUndoableDelete } from '../hooks/useUndoableDelete';
import { useSnackbar } from 'notistack';

export default function Categories() {
  const queryClient = useQueryClient();
  const { enqueueSnackbar } = useSnackbar();
  const deleteMutation = useDeleteCategory();
  
  // Wrap the mutation for undo functionality
  const wrappedDelete = useMemo(() => ({
    mutateAsync: async (id) => {
      await deleteMutation.mutateAsync(id);
    }
  }), [deleteMutation]);

  const { handleDelete, isPending } = useUndoableDelete(
    wrappedDelete,
    {
      delay: 5000,
      itemName: 'Category',
      onSuccess: (id, name) => {
        enqueueSnackbar(`Category "${name}" permanently deleted`, { 
          variant: 'success',
          autoHideDuration: 3000
        });
      },
      onError: (id, name, error) => {
        // Error is already handled in the hook, but you can add custom logic here
        console.error('Delete failed:', error);
      }
    }
  );

  const handleDeleteClick = (category) => {
    // For expensive deletes (categories with products), keep confirmation
    if (category.productCount > 0) {
      if (window.confirm(
        `Category "${category.name}" has ${category.productCount} product(s). ` +
        `Deleting it will also delete all associated products. Are you sure?`
      )) {
        deleteMutation.mutate(category.id, {
          onSuccess: () => {
            enqueueSnackbar(`Category "${category.name}" and its products deleted`, { 
              variant: 'success' 
            });
          }
        });
      }
      return;
    }
    
    // For cheap deletes, use undo
    handleDelete(category.id, category.name);
  };

  return (
    <Box>
      {/* Your existing category list UI */}
      {categories.map(category => (
        <Box key={category.id} sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
          <Typography sx={{ flex: 1 }}>{category.name}</Typography>
          <IconButton 
            onClick={() => handleDeleteClick(category)} 
            color="error"
            disabled={isPending}
          >
            <DeleteIcon />
          </IconButton>
        </Box>
      ))}
    </Box>
  );
}