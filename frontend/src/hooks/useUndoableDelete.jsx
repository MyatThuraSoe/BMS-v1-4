// src/hooks/useUndoableDelete.jsx
import { useState, useCallback, useRef, useEffect } from 'react';
import { useSnackbar } from 'notistack';
import { Button, Typography, Box } from '@mui/material';

export function useUndoableDelete(deleteMutation, { 
  delay = 5000, 
  itemName = 'item',
  onSuccess,
  onError 
} = {}) {
  const { enqueueSnackbar, closeSnackbar } = useSnackbar();
  const [pendingDelete, setPendingDelete] = useState(null);
  const timeoutRef = useRef(null);
  const isMountedRef = useRef(true);

  // Cleanup on unmount
  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, []);

  const executeDelete = useCallback(async (id, name) => {
    if (!isMountedRef.current) return;

    try {
      await deleteMutation.mutateAsync(id);
      
      if (isMountedRef.current) {
        setPendingDelete(null);
        onSuccess?.(id, name);
      }
    } catch (error) {
      if (isMountedRef.current) {
        setPendingDelete(null);
        enqueueSnackbar(
          `Failed to delete ${itemName} "${name}": ${error.message || 'Unknown error'}`, 
          { variant: 'error' }
        );
        onError?.(id, name, error);
      }
    }
  }, [deleteMutation, enqueueSnackbar, itemName, onSuccess, onError]);

  const handleDelete = useCallback((id, name) => {
    // Clear any existing timeout
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
    }

    setPendingDelete({ id, name });

    const actionKey = `undo-${id}-${Date.now()}`;
    
    enqueueSnackbar(
      null, // We'll use custom content
      {
        variant: 'default',
        persist: true,
        key: actionKey,
        content: (key) => (
          <Box 
            sx={{ 
              display: 'flex', 
              alignItems: 'center', 
              justifyContent: 'space-between',
              bgcolor: 'background.paper',
              borderRadius: 1,
              p: { xs: 1.5, sm: 2 },
              width: '100%',
              maxWidth: '100%',
              boxShadow: 3,
              color: 'text.primary'
            }}
          >
            <Typography variant="body2" sx={{ mr: 2, flex: 1, minWidth: 0 }}>
              {itemName} "{name}" deleted
            </Typography>
            <Button
              color="primary"
              size="small"
              onClick={() => {
                closeSnackbar(key);
                if (isMountedRef.current) {
                  setPendingDelete(null);
                }
                if (timeoutRef.current) {
                  clearTimeout(timeoutRef.current);
                }
                enqueueSnackbar('Deletion cancelled', { 
                  variant: 'info',
                  autoHideDuration: 2000
                });
              }}
              sx={{ fontWeight: 'bold', flexShrink: 0 }}
            >
              UNDO
            </Button>
          </Box>
        ),
        onExited: () => {
          // If toast is dismissed without clicking undo, execute the delete
          if (pendingDelete?.id === id && isMountedRef.current) {
            executeDelete(id, name);
          }
        }
      }
    );

    // Schedule the actual deletion
    timeoutRef.current = setTimeout(() => {
      if (isMountedRef.current) {
        closeSnackbar(actionKey);
        executeDelete(id, name);
      }
    }, delay);
  }, [enqueueSnackbar, closeSnackbar, executeDelete, pendingDelete, itemName, delay]);

  const cancelPending = useCallback(() => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
    if (isMountedRef.current) {
      setPendingDelete(null);
    }
  }, []);

  return { 
    handleDelete, 
    pendingDelete, 
    cancelPending,
    isPending: pendingDelete !== null
  };
}