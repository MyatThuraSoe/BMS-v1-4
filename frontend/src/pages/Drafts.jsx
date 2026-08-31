import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { Delete as DeleteIcon, ShoppingCart as CartIcon } from '@mui/icons-material';
import { useTranslation } from 'react-i18next';
import { formatCurrency, formatDateTime } from '../utils/helpers';
import { notifySuccess } from '../utils/notify';
import { readDrafts, writeDrafts } from '../utils/draftStorage';

const Drafts = () => {
  const { t } = useTranslation('pos');
  const navigate = useNavigate();
  const [drafts, setDrafts] = useState([]);
  const [selectedDraft, setSelectedDraft] = useState(null);

  useEffect(() => {
    setDrafts(readDrafts());
  }, []);

  const deleteDraft = (id) => {
    setDrafts((prevDrafts) => {
      const updated = prevDrafts.filter((d) => d.id !== id);
      writeDrafts(updated);
      return updated;
    });

    if (selectedDraft?.id === id) {
      setSelectedDraft(null);
    }

    notifySuccess(t('draft_deleted'));
  };

  return (
    <Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>{t('drafts_subtitle')}</Typography>
      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>{t('draft_created')}</TableCell>
              <TableCell>{t('draft_items')}</TableCell>
              <TableCell align="right">{t('action')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {drafts.length === 0 ? (
              <TableRow>
                <TableCell colSpan={3} align="center">{t('no_drafts')}</TableCell>
              </TableRow>
            ) : drafts.map((draft) => (
              <TableRow key={draft.id} hover onClick={() => setSelectedDraft(draft)} sx={{ cursor: 'pointer' }}>
                <TableCell>{formatDateTime(draft.createdAt)}</TableCell>
                <TableCell>{draft.items?.length || 0}</TableCell>
                <TableCell align="right" onClick={(event) => event.stopPropagation()}>
                  <IconButton color="error" aria-label={t('delete_draft')} onClick={() => deleteDraft(draft.id)}>
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
              <TableHead>
                <TableRow>
                  <TableCell>{t('product')}</TableCell>
                  <TableCell align="right">{t('quantity')}</TableCell>
                  <TableCell align="right">{t('price')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {(selectedDraft.items || []).map((item) => (
                  <TableRow key={item.productId ?? `${item.productName}-${item.quantity}`}>
                    <TableCell>{item.productName || item.name || t('product')}</TableCell>
                    <TableCell align="right">{item.quantity}</TableCell>
                    <TableCell align="right">{formatCurrency(Number(item.unitPrice ?? 0))}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSelectedDraft(null)}>{t('close')}</Button>
          <Button
            variant="contained"
            startIcon={<CartIcon />}
            onClick={() => selectedDraft && navigate(`/pos?draftId=${selectedDraft.id}`)}
          >
            {t('open_draft_in_pos')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default Drafts;
