import { useState } from 'react';
import {
  Box, Typography, Paper, Button, TextField, Dialog, DialogTitle, DialogContent,
  DialogActions, Stack, CircularProgress, Alert, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Chip, Grid,
} from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { shiftService } from '../api/services';
import { formatCurrency, formatDateTime } from '../utils/helpers';
import { notifySuccess, notifyError } from '../utils/notify';
import { useAuth } from '../context/AuthContext';

const CashShift = () => {
  const { user } = useAuth();
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
      notifySuccess('Shift opened');
      setOpenAmount('');
    },
    onError: (err) => notifyError(err.friendlyMessage || 'Failed to open shift'),
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
        notifySuccess(`Shift closed. Variance: ${formatCurrency(shift.variance)}`);
      } else {
        notifySuccess('Shift closed');
      }
    },
    onError: (err) => notifyError(err.friendlyMessage || 'Failed to close shift'),
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
      <Typography variant="h4" gutterBottom>Cash Shift</Typography>

      {!currentShift ? (
        <Paper sx={{ p: 4, maxWidth: 480 }}>
          <Typography variant="h6" gutterBottom>Start Shift</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
            Enter the opening cash amount in the drawer to start your shift.
          </Typography>
          <Stack spacing={2}>
            <TextField
              label="Opening Amount ($)"
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
              {openMutation.isPending ? 'Opening...' : 'Start Shift'}
            </Button>
          </Stack>
        </Paper>
      ) : (
        <>
          <Paper sx={{ p: 3, mb: 3 }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={2}>
              <Box>
                <Typography variant="h6" gutterBottom>
                  <Chip label="OPEN" color="success" size="small" sx={{ mr: 1 }} />
                  Shift Active
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  Opened {formatDateTime(currentShift.openingTime)}
                </Typography>
              </Box>
              <Button variant="contained" color="warning" onClick={() => setCloseDialogOpen(true)}>
                Close Shift
              </Button>
            </Stack>
          </Paper>

          <Grid container spacing={3} sx={{ mb: 3 }}>
            <Grid item xs={12} sm={6} md={3}>
              <Paper sx={{ p: 2, textAlign: 'center' }}>
                <Typography variant="body2" color="text.secondary">Opening Amount</Typography>
                <Typography variant="h5" fontWeight="bold">{formatCurrency(currentShift.openingAmount)}</Typography>
              </Paper>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Paper sx={{ p: 2, textAlign: 'center' }}>
                <Typography variant="body2" color="text.secondary">Cash Sales This Shift</Typography>
                <Typography variant="h5" fontWeight="bold">{formatCurrency(currentShift.cashSalesTotal)}</Typography>
              </Paper>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Paper sx={{ p: 2, textAlign: 'center' }}>
                <Typography variant="body2" color="text.secondary">Expected Drawer</Typography>
                <Typography variant="h5" fontWeight="bold">
                  {formatCurrency((Number(currentShift.openingAmount) || 0) + (Number(currentShift.cashSalesTotal) || 0))}
                </Typography>
              </Paper>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Paper sx={{ p: 2, textAlign: 'center' }}>
                <Typography variant="body2" color="text.secondary">Variance</Typography>
                <Typography variant="h5" fontWeight="bold" color="success.main">$0.00</Typography>
              </Paper>
            </Grid>
          </Grid>
        </>
      )}

      <Dialog open={closeDialogOpen} onClose={() => setCloseDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Close Shift</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Count the cash in the drawer and enter the total below.
          </Typography>
          <Stack spacing={2}>
            <TextField
              label="Actual Cash in Drawer ($)"
              type="number"
              value={closeAmount}
              onChange={(e) => setCloseAmount(e.target.value)}
              inputProps={{ min: 0, step: 0.01 }}
              fullWidth
              autoFocus
            />
            <TextField
              label="Notes (optional)"
              value={closeNotes}
              onChange={(e) => setCloseNotes(e.target.value)}
              multiline
              rows={2}
              fullWidth
            />
            {closeAmount && !isNaN(parseFloat(closeAmount)) && currentShift && (
              <Box>
                <Typography variant="subtitle2" gutterBottom>Expected vs Actual</Typography>
                <Table size="small">
                  <TableBody>
                    <TableRow>
                      <TableCell>Opening Amount</TableCell>
                      <TableCell align="right">{formatCurrency(currentShift.openingAmount)}</TableCell>
                    </TableRow>
                    <TableRow>
                      <TableCell>+ Cash Sales</TableCell>
                      <TableCell align="right">{formatCurrency(currentShift.cashSalesTotal)}</TableCell>
                    </TableRow>
                    <TableRow>
                      <TableCell sx={{ fontWeight: 'bold' }}>= Expected Drawer</TableCell>
                      <TableCell align="right" sx={{ fontWeight: 'bold' }}>
                        {formatCurrency(Number(currentShift.openingAmount) + Number(currentShift.cashSalesTotal))}
                      </TableCell>
                    </TableRow>
                    <TableRow>
                      <TableCell>Actual Counted</TableCell>
                      <TableCell align="right">{formatCurrency(parseFloat(closeAmount))}</TableCell>
                    </TableRow>
                    <TableRow>
                      <TableCell sx={{ fontWeight: 'bold' }}>Variance</TableCell>
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
                    The variance is {formatCurrency(parseFloat(closeAmount) - (Number(currentShift.openingAmount) + Number(currentShift.cashSalesTotal)))}.
                    {Math.abs(parseFloat(closeAmount) - (Number(currentShift.openingAmount) + Number(currentShift.cashSalesTotal))) > 10
                      ? ' Please double-check your count.'
                      : ' Small variances are normal.'}
                  </Alert>
                )}
              </Box>
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCloseDialogOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            onClick={handleCloseShift}
            disabled={!closeAmount || parseFloat(closeAmount) < 0 || closeMutation.isPending}
          >
            {closeMutation.isPending ? 'Closing...' : 'Close Shift'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default CashShift;
