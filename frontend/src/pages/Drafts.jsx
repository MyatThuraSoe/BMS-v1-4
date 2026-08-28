import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Box, Button, CircularProgress, Dialog, DialogActions, DialogContent,
  DialogTitle, IconButton, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Typography,
} from '@mui/material';
import { Delete as DeleteIcon, ShoppingCart as CartIcon } from '@mui/icons-material';
import { useTranslation } from 'react-i18next';
import { draftService } from '../api/services';
import { formatCurrency, formatDateTime } from '../utils/helpers';
import { notifyError, notifySuccess } from '../utils/notify';

const Drafts = () => {
  const { t } = useTranslation('pos');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [selectedDraft, setSelectedDraft] = useState(null);
  const { data, isLoading, isError } = useQuery({
    queryKey: ['drafts'],
    queryFn: () => draftService.getAll(),
  });
  const deleteMutation = useMutation({
    mutationFn: (id) => draftService.delete(id),
    onSuccess: () => {
      notifySuccess(t('draft_deleted'));
      setSelectedDraft(null);
      queryClient.invalidateQueries({ queryKey: ['drafts'] });
    },
    onError: (err) => notifyError(err.friendlyMessage || t('draft_delete_failed')),
  });
  const drafts = data?.data || [];

  if (isLoading) return <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box>;
  if (isError) return <Alert severity="error">{t('drafts_load_failed')}</Alert>;

  return (
    <Box>
      <Typography variant="h4" gutterBottom>{t('drafts_title')}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>{t('drafts_subtitle')}</Typography>
      <TableContainer component={Paper}>
        <Table>
          <TableHead><TableRow>
            <TableCell>{t('draft_created')}</TableCell>
            <TableCell>{t('draft_items')}</TableCell>
            <TableCell align="right">{t('action')}</TableCell>
          </TableRow></TableHead>
          <TableBody>
            {drafts.length === 0 ? (
              <TableRow><TableCell colSpan={3} align="center">{t('no_drafts')}</TableCell></TableRow>
            ) : drafts.map((draft) => (
              <TableRow key={draft.id} hover onClick={() => setSelectedDraft(draft)} sx={{ cursor: 'pointer' }}>
                <TableCell>{formatDateTime(draft.createdAt)}</TableCell>
                <TableCell>{draft.items?.length || 0}</TableCell>
                <TableCell align="right" onClick={(event) => event.stopPropagation()}>
                  <IconButton color="error" aria-label={t('delete_draft')} onClick={() => deleteMutation.mutate(draft.id)} disabled={deleteMutation.isPending}>
                    <DeleteIcon />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      <Dialog open={Boolean(selectedDraft)} onClose={() => setSelectedDraft(null)} maxWidth="sm" fullWidth>
        <DialogTitle>{t('draft_details')}</DialogTitle>
        <DialogContent dividers>
          {selectedDraft && (
            <Table size="small">
              <TableHead><TableRow>
                <TableCell>{t('product')}</TableCell>
                <TableCell align="right">{t('quantity')}</TableCell>
                <TableCell align="right">{t('price')}</TableCell>
              </TableRow></TableHead>
              <TableBody>{(selectedDraft.items || []).map((item) => (
                <TableRow key={item.productId}>
                  <TableCell>{item.productName}</TableCell>
                  <TableCell align="right">{item.quantity}</TableCell>
                  <TableCell align="right">{formatCurrency(item.unitPrice)}</TableCell>
                </TableRow>
              ))}</TableBody>
            </Table>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSelectedDraft(null)}>{t('close')}</Button>
          <Button variant="contained" startIcon={<CartIcon />} onClick={() => navigate(`/pos?draftId=${selectedDraft.id}`)}>
            {t('open_draft_in_pos')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default Drafts;