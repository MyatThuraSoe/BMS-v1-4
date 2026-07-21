import { useState } from 'react';
import {
  Box,
  Button,
  Card,
  CardContent,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  TextField,
  Typography,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Chip,
  Alert,
  Tabs,
  Tab,
} from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { enqueueSnackbar } from 'notistack';
import {
  AccountBalanceWallet as OpenShiftIcon,
  Close as CloseShiftIcon,
  History as HistoryIcon,
} from '@mui/icons-material';
import { format } from 'date-fns';
import { cashShiftService } from '../api/services';
import { useAuth } from '../context/AuthContext';

function CashShift() {
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const [activeTab, setActiveTab] = useState(0);
  const [openDialog, setOpenDialog] = useState(false);
  const [closeDialog, setCloseDialog] = useState(false);
  const [openingAmount, setOpeningAmount] = useState('');
  const [closingAmount, setClosingAmount] = useState('');
  const [notes, setNotes] = useState('');
  const [historyFilters, setHistoryFilters] = useState({
    startDate: '',
    endDate: '',
    cashierId: '',
  });

  // Fetch current shift
  const { data: currentShiftData, isLoading: loadingCurrentShift } = useQuery({
    queryKey: ['currentShift'],
    queryFn: () => cashShiftService.getCurrent(),
  });

  const currentShift = currentShiftData?.data;

  // Fetch shift history (Admin/Manager only)
  const { data: historyData, isLoading: loadingHistory } = useQuery({
    queryKey: ['shiftHistory', activeTab, historyFilters],
    queryFn: () => {
      if (activeTab !== 1) return { data: { content: [], totalElements: 0 } };
      const params = {};
      if (historyFilters.startDate) params.startDate = historyFilters.startDate;
      if (historyFilters.endDate) params.endDate = historyFilters.endDate;
      return cashShiftService.getHistory(params);
    },
    enabled: activeTab === 1 && (user?.roles?.some(r => r.name === 'ADMIN' || r.name === 'MANAGER')),
  });

  const shiftHistory = historyData?.data || { content: [], totalElements: 0 };

  // Open shift mutation
  const openShiftMutation = useMutation({
    mutationFn: (data) => cashShiftService.open(data),
    onSuccess: () => {
      queryClient.invalidateQueries(['currentShift']);
      enqueueSnackbar('Shift opened successfully', { variant: 'success' });
      setOpenDialog(false);
      setOpeningAmount('');
    },
    onError: (error) => {
      enqueueSnackbar(error.response?.data?.message || 'Failed to open shift', { variant: 'error' });
    },
  });

  // Close shift mutation
  const closeShiftMutation = useMutation({
    mutationFn: ({ id, data }) => cashShiftService.close(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries(['currentShift']);
      queryClient.invalidateQueries(['shiftHistory']);
      enqueueSnackbar('Shift closed successfully', { variant: 'success' });
      setCloseDialog(false);
      setClosingAmount('');
      setNotes('');
    },
    onError: (error) => {
      enqueueSnackbar(error.response?.data?.message || 'Failed to close shift', { variant: 'error' });
    },
  });

  const handleOpenDialog = () => {
    setOpenDialog(true);
  };

  const handleCloseDialog = () => {
    setOpenDialog(false);
    setOpeningAmount('');
  };

  const handleOpenShift = () => {
    if (!openingAmount || parseFloat(openingAmount) < 0) {
      enqueueSnackbar('Please enter a valid opening amount', { variant: 'warning' });
      return;
    }
    openShiftMutation.mutate({ openingAmount: parseFloat(openingAmount) });
  };

  const handleCloseShiftDialog = () => {
    setCloseDialog(false);
    setClosingAmount('');
    setNotes('');
  };

  const handleCloseShift = () => {
    if (!closingAmount || parseFloat(closingAmount) < 0) {
      enqueueSnackbar('Please enter a valid closing amount', { variant: 'warning' });
      return;
    }
    closeShiftMutation.mutate({
      id: currentShift.id,
      data: {
        closingAmount: parseFloat(closingAmount),
        notes: notes,
      },
    });
  };

  const handleTabChange = (event, newValue) => {
    setActiveTab(newValue);
  };

  const hasOpenShift = currentShift && currentShift.status === 'OPEN';

  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        Cash Shift Management
      </Typography>

      <Tabs value={activeTab} onChange={handleTabChange} sx={{ mb: 3 }}>
        <Tab label="Current Shift" icon={<OpenShiftIcon />} iconPosition="start" />
        {(user?.roles?.some(r => r.name === 'ADMIN' || r.name === 'MANAGER')) && (
          <Tab label="Shift History" icon={<HistoryIcon />} iconPosition="start" />
        )}
      </Tabs>

      {activeTab === 0 && (
        <Card sx={{ mb: 3 }}>
          <CardContent>
            {!hasOpenShift ? (
              <Box textAlign="center" py={6}>
                <OpenShiftIcon sx={{ fontSize: 80, color: 'text.secondary', mb: 2 }} />
                <Typography variant="h5" gutterBottom>
                  No Active Shift
                </Typography>
                <Typography variant="body1" color="text.secondary" sx={{ mb: 3 }}>
                  You need to open a shift before you can process sales
                </Typography>
                <Button
                  variant="contained"
                  size="large"
                  startIcon={<OpenShiftIcon />}
                  onClick={handleOpenDialog}
                  disabled={loadingCurrentShift}
                >
                  Open New Shift
                </Button>
              </Box>
            ) : (
              <Box>
                <Grid container spacing={3}>
                  <Grid item xs={12} md={6}>
                    <Alert severity="success" sx={{ mb: 2 }}>
                      <Typography variant="subtitle2">Shift is OPEN</Typography>
                      <Typography variant="body2">
                        Started at {format(new Date(currentShift.openingTime), 'PPP p')}
                      </Typography>
                    </Alert>
                  </Grid>
                  <Grid item xs={12} md={6} textAlign="right">
                    <Button
                      variant="outlined"
                      color="error"
                      startIcon={<CloseShiftIcon />}
                      onClick={() => setCloseDialog(true)}
                      disabled={closeShiftMutation.isPending}
                    >
                      Close Shift
                    </Button>
                  </Grid>
                </Grid>

                <Grid container spacing={3} sx={{ mt: 2 }}>
                  <Grid item xs={12} sm={6} md={3}>
                    <Paper sx={{ p: 2, textAlign: 'center' }}>
                      <Typography variant="body2" color="text.secondary">
                        Opening Amount
                      </Typography>
                      <Typography variant="h4" color="primary">
                        ${parseFloat(currentShift.openingAmount).toFixed(2)}
                      </Typography>
                    </Paper>
                  </Grid>
                  <Grid item xs={12} sm={6} md={3}>
                    <Paper sx={{ p: 2, textAlign: 'center' }}>
                      <Typography variant="body2" color="text.secondary">
                        Cash Sales
                      </Typography>
                      <Typography variant="h4">
                        ${(currentShift.sales?.reduce((sum, s) => sum + (s.totalAmount || 0), 0) || 0).toFixed(2)}
                      </Typography>
                    </Paper>
                  </Grid>
                  <Grid item xs={12} sm={6} md={3}>
                    <Paper sx={{ p: 2, textAlign: 'center' }}>
                      <Typography variant="body2" color="text.secondary">
                        Expected Amount
                      </Typography>
                      <Typography variant="h4">
                        ${(parseFloat(currentShift.openingAmount) + (currentShift.sales?.reduce((sum, s) => sum + (s.totalAmount || 0), 0) || 0)).toFixed(2)}
                      </Typography>
                    </Paper>
                  </Grid>
                  <Grid item xs={12} sm={6} md={3}>
                    <Paper sx={{ p: 2, textAlign: 'center' }}>
                      <Typography variant="body2" color="text.secondary">
                        Transactions
                      </Typography>
                      <Typography variant="h4">
                        {currentShift.sales?.length || 0}
                      </Typography>
                    </Paper>
                  </Grid>
                </Grid>

                {currentShift.sales && currentShift.sales.length > 0 && (
                  <Box sx={{ mt: 4 }}>
                    <Typography variant="h6" gutterBottom>
                      Today's Transactions
                    </Typography>
                    <TableContainer component={Paper}>
                      <Table size="small">
                        <TableHead>
                          <TableRow>
                            <TableCell>Invoice #</TableCell>
                            <TableCell align="right">Amount</TableCell>
                            <TableCell>Payment Method</TableCell>
                            <TableCell>Time</TableCell>
                            <TableCell>Status</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {currentShift.sales.map((sale) => (
                            <TableRow key={sale.id}>
                              <TableCell>{sale.invoiceNumber}</TableCell>
                              <TableCell align="right">${parseFloat(sale.totalAmount).toFixed(2)}</TableCell>
                              <TableCell>{sale.paymentMethod}</TableCell>
                              <TableCell>
                                {format(new Date(sale.saleDate), 'p')}
                              </TableCell>
                              <TableCell>
                                <Chip
                                  label={sale.isVoided ? 'Voided' : 'Completed'}
                                  size="small"
                                  color={sale.isVoided ? 'error' : 'success'}
                                />
                              </TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  </Box>
                )}
              </Box>
            )}
          </CardContent>
        </Card>
      )}

      {activeTab === 1 && (user?.roles?.some(r => r.name === 'ADMIN' || r.name === 'MANAGER')) && (
        <Card>
          <CardContent>
            <Grid container spacing={2} sx={{ mb: 3 }}>
              <Grid item xs={12} sm={3}>
                <TextField
                  fullWidth
                  label="Start Date"
                  type="date"
                  value={historyFilters.startDate}
                  onChange={(e) => setHistoryFilters({ ...historyFilters, startDate: e.target.value })}
                  InputLabelProps={{ shrink: true }}
                  size="small"
                />
              </Grid>
              <Grid item xs={12} sm={3}>
                <TextField
                  fullWidth
                  label="End Date"
                  type="date"
                  value={historyFilters.endDate}
                  onChange={(e) => setHistoryFilters({ ...historyFilters, endDate: e.target.value })}
                  InputLabelProps={{ shrink: true }}
                  size="small"
                />
              </Grid>
              <Grid item xs={12} sm={3}>
                <TextField
                  fullWidth
                  label="Cashier ID"
                  type="number"
                  value={historyFilters.cashierId}
                  onChange={(e) => setHistoryFilters({ ...historyFilters, cashierId: e.target.value })}
                  size="small"
                />
              </Grid>
              <Grid item xs={12} sm={3}>
                <Button
                  variant="contained"
                  fullWidth
                  onClick={() => queryClient.invalidateQueries(['shiftHistory'])}
                  sx={{ height: '40px' }}
                >
                  Apply Filters
                </Button>
              </Grid>
            </Grid>

            <TableContainer>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>ID</TableCell>
                    <TableCell>Cashier</TableCell>
                    <TableCell>Open Time</TableCell>
                    <TableCell>Close Time</TableCell>
                    <TableCell align="right">Opening</TableCell>
                    <TableCell align="right">Closing</TableCell>
                    <TableCell align="right">Expected</TableCell>
                    <TableCell align="right">Variance</TableCell>
                    <TableCell>Status</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {loadingHistory ? (
                    <TableRow>
                      <TableCell colSpan={9} align="center">
                        Loading...
                      </TableCell>
                    </TableRow>
                  ) : shiftHistory.content.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={9} align="center">
                        No shift records found
                      </TableCell>
                    </TableRow>
                  ) : (
                    shiftHistory.content.map((shift) => (
                      <TableRow key={shift.id}>
                        <TableCell>#{shift.id}</TableCell>
                        <TableCell>{shift.cashierName || `ID: ${shift.cashierId}`}</TableCell>
                        <TableCell>
                          {shift.openingTime ? format(new Date(shift.openingTime), 'PP p') : '-'}
                        </TableCell>
                        <TableCell>
                          {shift.closingTime ? format(new Date(shift.closingTime), 'PP p') : '-'}
                        </TableCell>
                        <TableCell align="right">${parseFloat(shift.openingAmount).toFixed(2)}</TableCell>
                        <TableCell align="right">
                          {shift.closingAmount ? `$${parseFloat(shift.closingAmount).toFixed(2)}` : '-'}
                        </TableCell>
                        <TableCell align="right">
                          {shift.expectedAmount ? `$${parseFloat(shift.expectedAmount).toFixed(2)}` : '-'}
                        </TableCell>
                        <TableCell align="right">
                          {shift.variance !== null && shift.variance !== undefined ? (
                            <Typography
                              color={parseFloat(shift.variance) < 0 ? 'error' : parseFloat(shift.variance) > 0 ? 'success.main' : 'text.secondary'}
                              fontWeight="bold"
                            >
                              ${parseFloat(shift.variance).toFixed(2)}
                            </Typography>
                          ) : (
                            '-'
                          )}
                        </TableCell>
                        <TableCell>
                          <Chip
                            label={shift.status}
                            size="small"
                            color={shift.status === 'OPEN' ? 'success' : 'default'}
                          />
                        </TableCell>
                      </TableRow>
                    ))
                  )}
                </TableBody>
              </Table>
            </TableContainer>
          </CardContent>
        </Card>
      )}

      {/* Open Shift Dialog */}
      <Dialog open={openDialog} onClose={handleCloseDialog}>
        <DialogTitle>Open New Cash Shift</DialogTitle>
        <DialogContent sx={{ minWidth: 300 }}>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Enter the starting cash amount in your drawer
          </Typography>
          <TextField
            autoFocus
            margin="dense"
            label="Opening Amount ($)"
            type="number"
            fullWidth
            value={openingAmount}
            onChange={(e) => setOpeningAmount(e.target.value)}
            inputProps={{ step: '0.01', min: '0' }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={handleCloseDialog}>Cancel</Button>
          <Button
            onClick={handleOpenShift}
            variant="contained"
            disabled={openShiftMutation.isPending}
          >
            {openShiftMutation.isPending ? 'Opening...' : 'Open Shift'}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Close Shift Dialog */}
      <Dialog open={closeDialog} onClose={handleCloseShiftDialog}>
        <DialogTitle>Close Cash Shift</DialogTitle>
        <DialogContent sx={{ minWidth: 350 }}>
          <Box sx={{ mb: 2 }}>
            <Typography variant="body2" color="text.secondary">
              Shift opened: {currentShift?.openingTime ? format(new Date(currentShift.openingTime), 'PPP p') : '-'}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Opening amount: ${parseFloat(currentShift?.openingAmount || 0).toFixed(2)}
            </Typography>
            <Typography variant="body2">
              Cash sales: ${(currentShift?.sales?.reduce((sum, s) => sum + (s.totalAmount || 0), 0) || 0).toFixed(2)}
            </Typography>
            <Typography variant="h6" sx={{ mt: 2 }}>
              Expected: ${(parseFloat(currentShift?.openingAmount || 0) + (currentShift?.sales?.reduce((sum, s) => sum + (s.totalAmount || 0), 0) || 0)).toFixed(2)}
            </Typography>
          </Box>
          <TextField
            autoFocus
            margin="dense"
            label="Actual Closing Amount ($)"
            type="number"
            fullWidth
            value={closingAmount}
            onChange={(e) => setClosingAmount(e.target.value)}
            inputProps={{ step: '0.01', min: '0' }}
            helperText="Count the cash in your drawer"
          />
          <TextField
            margin="dense"
            label="Notes (optional)"
            type="text"
            fullWidth
            multiline
            rows={2}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            helperText="Any discrepancies or comments"
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={handleCloseShiftDialog}>Cancel</Button>
          <Button
            onClick={handleCloseShift}
            variant="contained"
            color="error"
            disabled={closeShiftMutation.isPending}
          >
            {closeShiftMutation.isPending ? 'Closing...' : 'Close Shift'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

export default CashShift;
