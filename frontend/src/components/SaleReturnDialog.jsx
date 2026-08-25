import { useEffect, useState } from 'react';
import {
  Box, Typography, Button, Dialog, DialogTitle, DialogContent, DialogActions,
  CircularProgress, TextField, Divider, Paper, Table, TableHead, TableBody,
  TableCell, TableContainer, TableRow, Alert,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AssignmentReturn as ReturnIcon } from '@mui/icons-material';
import { saleService } from '../api/services';
import { formatCurrency } from '../utils/helpers';
import { notifySuccess, notifyError } from '../utils/notify';
import { useTranslation } from 'react-i18next';

// Cache prefixes that can reflect a return: sale data, stock, and money reports.
const INVALIDATION_KEYS = [
  ['sale'],
  ['sales'],
  ['receipt'],
  ['recentSales'],
  ['products'],
  ['products-pos'],
  ['low-stock'],
  ['inventoryReport'],
  ['financialSummary'],
  ['dailySales'],
  ['salesTrend'],
  ['accountingSummary'],
  ['profitSummary'],
  ['profitTrend'],
  ['topProducts'],
  ['topSellingProducts'],
  ['topCategories'],
  ['cashierPerformance'],
  ['inventory-summary'],
  ['stock-movements'],
  ['movement-stats'],
];

/**
 * Shared Sale Return dialog.
 * Fetches the authoritative returnable snapshot from
 * GET /api/sales/{id}/returnable-items — quantities and refund amounts shown
 * here are backend-computed from the original sale prices.
 */
const SaleReturnDialog = ({ open, onClose, saleId }) => {
  const { t } = useTranslation('sales');
  const queryClient = useQueryClient();
  const [quantities, setQuantities] = useState({});
  const [reason, setReason] = useState('');
  const [step, setStep] = useState('form'); // 'form' | 'confirm'

  const { data, isLoading } = useQuery({
    queryKey: ['returnable-items', saleId],
    queryFn: () => saleService.getReturnableItems(saleId),
    enabled: open && !!saleId,
  });

  useEffect(() => {
    if (open) {
      setQuantities({});
      setReason('');
      setStep('form');
    }
  }, [open]);

  const items = data?.data?.items || [];
  const returnableItems = items.filter((item) => (item.quantityReturnable || 0) > 0);

  const selectedLines = returnableItems
    .map((item) => ({
      item,
      qty: Number(quantities[item.saleItemId] || 0),
    }))
    .filter((line) => line.qty > 0);

  const returnTotal = selectedLines.reduce(
    (sum, line) => sum + Number(line.item.unitRefundAmount || 0) * line.qty,
    0
  );

  const mutation = useMutation({
    mutationFn: (payload) => saleService.createSaleReturn(saleId, payload),
    onSuccess: () => {
      notifySuccess(t('return_success'));
      INVALIDATION_KEYS.forEach((key) => queryClient.invalidateQueries({ queryKey: key }));
      queryClient.invalidateQueries({ queryKey: ['returnable-items', saleId] });
      handleClose();
    },
    onError: (err) =>
      notifyError(err.response?.data?.message || err.friendlyMessage || t('return_failed')),
  });

  function handleClose() {
    if (mutation.isPending) return;
    setQuantities({});
    setReason('');
    setStep('form');
    onClose();
  }

  const setQuantity = (item, value) => {
    const max = item.quantityReturnable || 0;
    const qty = Math.max(0, Math.min(max, Math.floor(Number(value) || 0)));
    setQuantities((cur) => ({ ...cur, [item.saleItemId]: qty }));
  };

  const handleConfirm = () => {
    const payload = {
      reason: reason.trim(),
      items: selectedLines.map((line) => ({
        saleItemId: line.item.saleItemId,
        quantity: line.qty,
      })),
    };
    if (!payload.reason || payload.items.length === 0) return;
    mutation.mutate(payload);
  };

  const canSubmit = selectedLines.length > 0 && reason.trim().length > 0;

  return (
    <Dialog open={open} onClose={handleClose} fullWidth maxWidth="md">
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <ReturnIcon color="warning" />
        {step === 'form' ? t('return_items') : t('confirm_return')}
      </DialogTitle>
      <DialogContent dividers>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {t('invoice_no')} {data?.data?.invoiceNumber || saleId}
        </Typography>

        {isLoading ? (
          <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
            <CircularProgress />
          </Box>
        ) : returnableItems.length === 0 ? (
          <Alert severity="info">{t('nothing_to_return')}</Alert>
        ) : step === 'form' ? (
          <>
            <TableContainer component={Paper} elevation={0} sx={{ border: '1px solid', borderColor: 'divider', mb: 2 }}>
              <Table size="small">
                <TableHead>
                  <TableRow sx={{ '& th': { bgcolor: 'grey.50', fontWeight: 700, color: 'text.secondary', borderBottom: '2px solid', borderColor: 'divider' } }}>
                    <TableCell>{t('product')}</TableCell>
                    <TableCell align="right">{t('sold_qty')}</TableCell>
                    <TableCell align="right">{t('already_returned')}</TableCell>
                    <TableCell align="right">{t('returnable_qty')}</TableCell>
                    <TableCell align="right" sx={{ width: 110 }}>{t('return_qty')}</TableCell>
                    <TableCell align="right">{t('refund_amount')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {returnableItems.map((item) => {
                    const qty = Number(quantities[item.saleItemId] || 0);
                    return (
                      <TableRow key={item.saleItemId} sx={{ '&:last-child td': { border: 0 } }}>
                        <TableCell sx={{ fontWeight: 500 }}>{item.productName}</TableCell>
                        <TableCell align="right">{item.quantitySold}</TableCell>
                        <TableCell align="right">
                          {item.quantityAlreadyReturned > 0
                            ? <Typography variant="body2" color="warning.main" fontWeight={600}>{item.quantityAlreadyReturned}</Typography>
                            : <Typography variant="body2" color="text.disabled">-</Typography>}
                        </TableCell>
                        <TableCell align="right" sx={{ fontWeight: 600 }}>{item.quantityReturnable}</TableCell>
                        <TableCell align="right">
                          <TextField
                            size="small"
                            type="number"
                            inputProps={{ min: 0, max: item.quantityReturnable }}
                            value={qty > 0 ? qty : ''}
                            onChange={(e) => setQuantity(item, e.target.value)}
                            sx={{ width: 90 }}
                          />
                        </TableCell>
                        <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>
                          {qty > 0 ? formatCurrency(Number(item.unitRefundAmount || 0) * qty) : '-'}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </TableContainer>

            <TextField
              fullWidth
              required
              multiline
              rows={2}
              label={t('reason')}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />

            <Divider sx={{ my: 2 }} />
            <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
              <Typography fontWeight={600}>{t('return_total')}</Typography>
              <Typography fontWeight={700} color="warning.main" sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>
                {formatCurrency(returnTotal)}
              </Typography>
            </Box>
          </>
        ) : (
          <>
            <Alert severity="warning" icon={<ReturnIcon />} sx={{ mb: 2 }}>
              {t('return_confirm_message', { amount: formatCurrency(returnTotal), count: selectedLines.reduce((s, l) => s + l.qty, 0) })}
            </Alert>
            <TableContainer component={Paper} elevation={0} sx={{ border: '1px solid', borderColor: 'divider', mb: 2 }}>
              <Table size="small">
                <TableHead>
                  <TableRow sx={{ '& th': { bgcolor: 'grey.50', fontWeight: 700, color: 'text.secondary' } }}>
                    <TableCell>{t('product')}</TableCell>
                    <TableCell align="right">{t('return_qty')}</TableCell>
                    <TableCell align="right">{t('refund_amount')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {selectedLines.map(({ item, qty }) => (
                    <TableRow key={item.saleItemId}>
                      <TableCell>{item.productName}</TableCell>
                      <TableCell align="right">{qty}</TableCell>
                      <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>
                        {formatCurrency(Number(item.unitRefundAmount || 0) * qty)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
              <Typography fontWeight={600}>{t('reason')}: {reason.trim()}</Typography>
              <Typography fontWeight={700} color="warning.main" sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>
                {formatCurrency(returnTotal)}
              </Typography>
            </Box>
          </>
        )}
      </DialogContent>
      <DialogActions>
        {step === 'form' ? (
          <>
            <Button onClick={handleClose}>{t('cancel')}</Button>
            <Button
              onClick={() => setStep('confirm')}
              color="warning"
              variant="contained"
              disabled={!canSubmit}
            >
              {t('return')}
            </Button>
          </>
        ) : (
          <>
            <Button onClick={() => setStep('form')} disabled={mutation.isPending}>{t('back')}</Button>
            <Button
              onClick={handleConfirm}
              color="warning"
              variant="contained"
              startIcon={mutation.isPending ? <CircularProgress size={20} sx={{ color: 'white' }} /> : null}
              disabled={mutation.isPending}
            >
              {mutation.isPending ? t('processing') : t('confirm_return')}
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  );
};

export default SaleReturnDialog;
