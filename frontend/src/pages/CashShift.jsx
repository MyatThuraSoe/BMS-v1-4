import { useState } from 'react';
import {
  Box, Typography, Paper, Button, TextField, Dialog, DialogTitle, DialogContent,
  DialogActions, Stack, CircularProgress, Alert, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Chip, Grid,
} from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { shiftService } from '../api/services';
import { formatCurrency, formatDateTime } from '../utils/helpers';
import { notifySuccess, notifyError } from '../utils/notify';
import { useAuth } from '../context/AuthContext';

const CashShift = () => {
  const { user } = useAuth();
  const { t } = useTranslation('cash');
  const queryClient = useQueryClient();

  const [openAmount, setOpenAmount] = useState('');
  const [closeDialogOpen, setCloseDialogOpen] = useState(false);
  const [closeAmount, setCloseAmount] = useState('');
  const [closeNotes, setCloseNotes] = useState('');

    const { data: currentData, isLoading: currentLoading } = useQuery({
    queryKey: ['currentShift'],
    queryFn: () => shiftService.getCurrentShift(),
    refetchInterval: 30000, // Keeps it updated while you are on the page
    refetchOnMount: 'always', // 👈 Forces a fresh call to the server every time you open the page
  });

  const currentShift = currentData?.data;

  const openMutation = useMutation({
    mutationFn: (amount) => shiftService.openShift(amount),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['currentShift'] });
      notifySuccess(t('shift_opened'));
      setOpenAmount('');
    },
    onError: (err) => notifyError(err.friendlyMessage || t('failed_to_open_shift')),
  });

  const closeMutation = useMutation({
    mutationFn: ({ id, closingAmount, notes }) => shiftService.closeShift(id, closingAmount, notes),
    onSuccess: (response) => {
      queryClient.invalidateQueries({ queryKey: ['currentShift'] });
      setCloseDialogOpen(false);
      setCloseAmount('');
      setCloseNotes('');
      const shift = response?.data;
      if (shift && Math.abs(Number(shift.variance)) > 0) {
        notifySuccess(t('shift_closed_variance', { variance: formatCurrency(shift.variance) }));
      } else {
        notifySuccess(t('shift_closed'));
      }
    },
    onError: (err) => notifyError(err.friendlyMessage || t('failed_to_close_shift')),
  });

  const handleOpenShift = () => {
    const amount = parseFloat(openAmount);
    if (isNaN(amount) || amount < 0) return;
    openMutation.mutate(amount);
  };

  const handleCloseShift = () => {
    const amount = parseFloat(closeAmount);
    if (isNaN(amount) || amount < 0 || !currentShift?.id) return;
    closeMutation.mutate({ id: currentShift.id, closingAmount: amount, notes: closeNotes });
  };

  const variance = currentShift
    ? Number(currentShift.expectedAmount || 0) - Number(currentShift.openingAmount || 0) + Number(currentShift.closingAmount || 0)
    : 0;

  if (currentLoading) {
    return <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box>;
  }

  return (
    <Box>
    

      {!currentShift ? (
        <Paper sx={{ p: 4, maxWidth: 480 }}>
          <Typography variant="h6" gutterBottom>{t('start_shift')}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
            {t('opening_amount_help')}
          </Typography>
          <Stack spacing={2}>
            <TextField
              label={t('opening_amount_label')}
              type="number"
              value={openAmount}
              onChange={(e) => setOpenAmount(e.target.value)}
              inputProps={{ min: 0, step: 0.01 }}
              fullWidth
              autoFocus
            />
            <Button
              variant="contained"
              size="large"
              onClick={handleOpenShift}
              disabled={!openAmount || parseFloat(openAmount) < 0 || openMutation.isPending}
            >
              {openMutation.isPending ? t('opening') : t('start_shift')}
            </Button>
          </Stack>
        </Paper>
      ) : (
        <>
          <Paper sx={{ p: 3, mb: 3 }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={2}>
              <Box>
                <Typography variant="h6" gutterBottom>
                  <Chip label={t('status_open')} color="success" size="small" sx={{ mr: 1 }} />
                  {t('shift_active')}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {t('opened_at', { time: formatDateTime(currentShift.openingTime) })}
                </Typography>
              </Box>
              <Button variant="contained" color="warning" onClick={() => setCloseDialogOpen(true)}>
                {t('close_shift')}
              </Button>
            </Stack>
          </Paper>

          <Grid container spacing={3} sx={{ mb: 3 }}>
            <Grid item xs={12} sm={6} md={3}>
              <Paper sx={{ p: 2, textAlign: 'center' }}>
                <Typography variant="body2" color="text.secondary">{t('opening_amount')}</Typography>
                <Typography variant="h5" fontWeight="bold">{formatCurrency(currentShift.openingAmount)}</Typography>
              </Paper>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Paper sx={{ p: 2, textAlign: 'center' }}>
                <Typography variant="body2" color="text.secondary">{t('cash_sales_this_shift')}</Typography>
                <Typography variant="h5" fontWeight="bold">{formatCurrency(currentShift.cashSalesTotal)}</Typography>
              </Paper>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Paper sx={{ p: 2, textAlign: 'center' }}>
                <Typography variant="body2" color="text.secondary">{t('expected_drawer')}</Typography>
                <Typography variant="h5" fontWeight="bold">
                  {formatCurrency((Number(currentShift.openingAmount) || 0) + (Number(currentShift.cashSalesTotal) || 0))}
                </Typography>
              </Paper>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Paper sx={{ p: 2, textAlign: 'center' }}>
                <Typography variant="body2" color="text.secondary">{t('variance')}</Typography>
                <Typography variant="h5" fontWeight="bold" color="success.main">$0.00</Typography>
              </Paper>
            </Grid>
          </Grid>
        </>
      )}

      <Dialog open={closeDialogOpen} onClose={() => setCloseDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{t('close_shift')}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {t('close_shift_help')}
          </Typography>
          <Stack spacing={2}>
            <TextField
              label={t('actual_cash_in_drawer_label')}
              type="number"
              value={closeAmount}
              onChange={(e) => setCloseAmount(e.target.value)}
              inputProps={{ min: 0, step: 0.01 }}
              fullWidth
              autoFocus
            />
            <TextField
              label={t('notes_optional')}
              value={closeNotes}
              onChange={(e) => setCloseNotes(e.target.value)}
              multiline
              rows={2}
              fullWidth
            />
            {closeAmount && !isNaN(parseFloat(closeAmount)) && currentShift && (
              <Box>
                <Typography variant="subtitle2" gutterBottom>{t('expected_vs_actual')}</Typography>
                <Table size="small">
                  <TableBody>
                    <TableRow>
                      <TableCell>{t('opening_amount')}</TableCell>
                      <TableCell align="right">{formatCurrency(currentShift.openingAmount)}</TableCell>
                    </TableRow>
                    <TableRow>
                      <TableCell>{t('cash_sales_plus')}</TableCell>
                      <TableCell align="right">{formatCurrency(currentShift.cashSalesTotal)}</TableCell>
                    </TableRow>
                    <TableRow>
                      <TableCell sx={{ fontWeight: 'bold' }}>{t('expected_drawer_equals')}</TableCell>
                      <TableCell align="right" sx={{ fontWeight: 'bold' }}>
                        {formatCurrency(Number(currentShift.openingAmount) + Number(currentShift.cashSalesTotal))}
                      </TableCell>
                    </TableRow>
                    <TableRow>
                      <TableCell>{t('actual_counted')}</TableCell>
                      <TableCell align="right">{formatCurrency(parseFloat(closeAmount))}</TableCell>
                    </TableRow>
                    <TableRow>
                      <TableCell sx={{ fontWeight: 'bold' }}>{t('variance')}</TableCell>
                      <TableCell align="right" sx={{
                        fontWeight: 'bold',
                        color: (parseFloat(closeAmount) - (Number(currentShift.openingAmount) + Number(currentShift.cashSalesTotal))) === 0
                          ? 'success.main'
                          : Math.abs(parseFloat(closeAmount) - (Number(currentShift.openingAmount) + Number(currentShift.cashSalesTotal))) > 10
                            ? 'error.main'
                            : 'warning.main',
                      }}>
                        {formatCurrency(parseFloat(closeAmount) - (Number(currentShift.openingAmount) + Number(currentShift.cashSalesTotal)))}
                      </TableCell>
                    </TableRow>
                  </TableBody>
                </Table>
                {parseFloat(closeAmount) !== (Number(currentShift.openingAmount) + Number(currentShift.cashSalesTotal)) && (
                  <Alert severity="info" sx={{ mt: 2 }}>
                    {t('variance_is', { amount: formatCurrency(parseFloat(closeAmount) - (Number(currentShift.openingAmount) + Number(currentShift.cashSalesTotal))) })}
                    {Math.abs(parseFloat(closeAmount) - (Number(currentShift.openingAmount) + Number(currentShift.cashSalesTotal))) > 10
                      ? t('double_check_count')
                      : t('small_variances_normal')}
                  </Alert>
                )}
              </Box>
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCloseDialogOpen(false)}>{t('cancel')}</Button>
          <Button
            variant="contained"
            onClick={handleCloseShift}
            disabled={!closeAmount || parseFloat(closeAmount) < 0 || closeMutation.isPending}
          >
            {closeMutation.isPending ? t('closing') : t('close_shift')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default CashShift;
