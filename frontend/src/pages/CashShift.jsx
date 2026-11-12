import { useState } from 'react';
import {
  Box, Typography, Paper, Button, TextField, Dialog, DialogTitle, DialogContent,
  DialogActions, Stack, CircularProgress, Alert, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Chip, Grid, MenuItem, ToggleButton,
  ToggleButtonGroup, FormControlLabel, Checkbox, Divider,
} from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { shiftService } from '../api/services';
import { formatCurrency, formatDateTime, preventNumberScroll } from '../utils/helpers';
import { notifySuccess, notifyError, notifyWarning } from '../utils/notify';
import { useAuth } from '../context/AuthContext';
import ShiftHistory from './ShiftHistory';

// Mirrors CashVarianceUtil.DEFAULT_TOLERANCE. The backend sends the real value
// on every shift response; this only covers the brief window before a response
// arrives, so the two never drift apart in what the user is shown.
const FALLBACK_VARIANCE_TOLERANCE = 10;

const SHIFT_TYPE_KEYS = {
  CASH_DRAWER: 'shift_type_cash_drawer',
  NON_CASH: 'shift_type_non_cash',
  SUPERVISOR: 'shift_type_supervisor',
};

const PAYMENT_METHOD_ORDER = [
  'CASH', 'MOBILE_WALLET', 'CARD', 'BANK_TRANSFER', 'CREDIT', 'STORE_CREDIT', 'OTHER', 'MIXED',
];

const CashShift = () => {
  const { user, isManager } = useAuth();
  const { t } = useTranslation('cash');
  const queryClient = useQueryClient();

  const [startType, setStartType] = useState('CASH_DRAWER');
  const [openAmount, setOpenAmount] = useState('');
  const [openNotes, setOpenNotes] = useState('');
  const [approverUsername, setApproverUsername] = useState('');
  const [approverPassword, setApproverPassword] = useState('');

  const [closeDialogOpen, setCloseDialogOpen] = useState(false);
  const [closeCounted, setCloseCounted] = useState('');
  const [closeNotes, setCloseNotes] = useState('');
  const [closeDrawer, setCloseDrawer] = useState(true);
  const [forceClose, setForceClose] = useState(false);
  const [forceReason, setForceReason] = useState('');

  const [operationOpen, setOperationOpen] = useState(false);
  const [operationType, setOperationType] = useState('SAFE_DROP');
  const [operationAmount, setOperationAmount] = useState('');
  const [operationReason, setOperationReason] = useState('');

  const { data: currentData, isLoading: currentLoading } = useQuery({
    queryKey: ['currentShift'],
    queryFn: () => shiftService.getCurrentShift(),
    refetchInterval: 30000,
    refetchOnMount: 'always',
  });
  const currentShift = currentData?.data;

  const { data: contextData } = useQuery({
    queryKey: ['shiftStartContext'],
    queryFn: () => shiftService.getStartContext(),
    enabled: !currentShift,
    refetchInterval: currentShift ? false : 30000,
    refetchOnMount: 'always',
  });
  const context = contextData?.data;

  const isCashShift = currentShift?.shiftType === 'CASH_DRAWER';
  const drawerSessionOpen = currentShift?.drawerSessionStatus === 'OPEN';
  const isDrawerOwner = currentShift?.drawerOwnerId != null
    && Number(currentShift.drawerOwnerId) === Number(user?.id);
  const canCloseDrawer = isCashShift && drawerSessionOpen && isDrawerOwner;
  const canUseDrawerOperations = isCashShift && drawerSessionOpen && currentShift?.drawerSessionId != null;

  const invalidateShiftData = () => {
    queryClient.invalidateQueries({ queryKey: ['currentShift'] });
    queryClient.invalidateQueries({ queryKey: ['shiftStartContext'] });
    queryClient.invalidateQueries({ queryKey: ['activeShifts'] });
    queryClient.invalidateQueries({ queryKey: ['activeDrawerSessions'] });
    queryClient.invalidateQueries({ queryKey: ['shift-history'] });
    queryClient.invalidateQueries({ queryKey: ['drawer-session-history'] });
  };

  const openMutation = useMutation({
    mutationFn: (payload) => shiftService.openShift(payload),
    onSuccess: () => {
      invalidateShiftData();
      notifySuccess(t('shift_opened'));
      setOpenAmount('');
      setOpenNotes('');
      setApproverUsername('');
      setApproverPassword('');
    },
    onError: (err) => notifyError(err.friendlyMessage || t('failed_to_open_shift')),
  });

  const closeMutation = useMutation({
    mutationFn: (payload) => shiftService.closeShift(currentShift.id, payload),
    onSuccess: (response) => {
      invalidateShiftData();
      setCloseDialogOpen(false);
      setCloseCounted('');
      setCloseNotes('');
      setForceClose(false);
      setForceReason('');
      const shift = response?.data;
      if (shift?.variance != null && Math.abs(Number(shift.variance)) > 0) {
        if (shift.varianceExceedsTolerance) {
          notifyWarning(t('shift_closed_large_variance', { variance: formatCurrency(shift.variance) }));
        } else {
          notifySuccess(t('shift_closed_variance', { variance: formatCurrency(shift.variance) }));
        }
      } else {
        notifySuccess(t('shift_closed'));
      }
    },
    onError: (err) => notifyError(err.friendlyMessage || t('failed_to_close_shift')),
  });

  const drawerOperationMutation = useMutation({
    mutationFn: (payload) => shiftService.performDrawerMovement(currentShift.drawerSessionId, payload),
    onSuccess: () => {
      invalidateShiftData();
      setOperationOpen(false);
      setOperationAmount('');
      setOperationReason('');
      notifySuccess(t('drawer_operation_recorded'));
    },
    onError: (err) => notifyError(err.friendlyMessage || t('drawer_operation_failed')),
  });

  const handleOpenShift = () => {
    if (startType === 'CASH_DRAWER') {
      if (context && !context.canOpenCashDrawer) {
        notifyError(t('drawer_not_available'));
        return;
      }
      if (context?.drawerStatus === 'CLOSED') {
        const amount = parseFloat(openAmount);
        if (isNaN(amount) || amount < 0) return;
      }
      if (context?.drawerStatus === 'OPEN' && context?.managerApprovalRequired && !context?.managerOrAdmin) {
        if (!approverUsername.trim() || !approverPassword) {
          notifyError(t('manager_approval_required'));
          return;
        }
      }
    }

    openMutation.mutate({
      shiftType: startType,
      drawerId: context?.drawerId ?? null,
      openingAmount: startType === 'CASH_DRAWER' && context?.drawerStatus === 'CLOSED'
        ? parseFloat(openAmount) : null,
      approverUsername: startType === 'CASH_DRAWER' ? approverUsername || null : null,
      approverPassword: startType === 'CASH_DRAWER' ? approverPassword || null : null,
      notes: openNotes || null,
    });
  };

  const openCloseDialog = () => {
    setCloseCounted('');
    setCloseNotes('');
    setForceClose(false);
    setForceReason('');
    setCloseDrawer(canCloseDrawer);
    setCloseDialogOpen(true);
  };

  const handleCloseShift = () => {
    if (!currentShift?.id) return;
    const closingDrawerToo = canCloseDrawer && closeDrawer;
    if (closingDrawerToo && (closeCounted === '' || isNaN(parseFloat(closeCounted)) || parseFloat(closeCounted) < 0)) {
      return;
    }

    closeMutation.mutate({
      countedCash: closingDrawerToo ? parseFloat(closeCounted) : null,
      closingNote: closeNotes || null,
      closeDrawer: closingDrawerToo,
      forceClose,
      forceReason: forceClose ? forceReason : null,
    });
  };

  const handleDrawerOperation = () => {
    const numericAmount = operationType === 'NO_SALE_OPEN' ? 0 : parseFloat(operationAmount);
    if (operationType !== 'NO_SALE_OPEN' && (isNaN(numericAmount) || numericAmount <= 0)) return;
    if ((operationType === 'PAY_OUT' || operationType === 'ADJUSTMENT')
      && !operationReason.trim()) {
      return;
    }
    drawerOperationMutation.mutate({
      movementType: operationType,
      amount: numericAmount,
      reason: operationReason || null,
    });
  };

  const openingAmount = Number(currentShift?.openingAmount) || 0;
  const cashSales = Number(currentShift?.cashSalesTotal) || 0;
  const returnsTotal = Number(currentShift?.returnsTotal) || 0;
  const nonCashSales = Number(currentShift?.nonCashSalesTotal) || 0;
  const expectedAmount = currentShift?.expectedAmount != null
    ? Number(currentShift.expectedAmount) : null;
  const varianceTolerance = Number(currentShift?.varianceTolerance) > 0
    ? Number(currentShift.varianceTolerance)
    : FALLBACK_VARIANCE_TOLERANCE;
  const exceedsTolerance = (amount) => Math.abs(amount) > varianceTolerance;

  const paymentBreakdown = currentShift?.paymentBreakdown || {};
  const breakdownEntries = Object.entries(paymentBreakdown)
    .filter(([, amount]) => Number(amount) !== 0)
    .sort(([a], [b]) => {
      const ai = PAYMENT_METHOD_ORDER.indexOf(a);
      const bi = PAYMENT_METHOD_ORDER.indexOf(b);
      return (ai === -1 ? 99 : ai) - (bi === -1 ? 99 : bi);
    });

  if (currentLoading) {
    return <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box>;
  }

  return (
    <Box>
      {!currentShift ? (
        <Paper sx={{ p: 4, maxWidth: 680 }}>
          <Typography variant="h6" gutterBottom>{t('start_shift')}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
            {t('start_shift_subtitle')}
          </Typography>

          <ToggleButtonGroup
            exclusive
            fullWidth
            value={startType}
            onChange={(event, value) => { if (value) setStartType(value); }}
            sx={{ mb: 3 }}
          >
            <ToggleButton value="CASH_DRAWER" sx={{ textTransform: 'none', py: 1.5 }}>
              <Box>
                <Typography variant="subtitle2">{t('shift_type_cash_drawer')}</Typography>
                <Typography variant="caption" color="text.secondary">
                  {t('shift_type_cash_drawer_help')}
                </Typography>
              </Box>
            </ToggleButton>
            <ToggleButton value="NON_CASH" sx={{ textTransform: 'none', py: 1.5 }}>
              <Box>
                <Typography variant="subtitle2">{t('shift_type_non_cash')}</Typography>
                <Typography variant="caption" color="text.secondary">
                  {t('shift_type_non_cash_help')}
                </Typography>
              </Box>
            </ToggleButton>
            {isManager() && (
              <ToggleButton value="SUPERVISOR" sx={{ textTransform: 'none', py: 1.5 }}>
                <Box>
                  <Typography variant="subtitle2">{t('shift_type_supervisor')}</Typography>
                  <Typography variant="caption" color="text.secondary">
                    {t('shift_type_supervisor_help')}
                  </Typography>
                </Box>
              </ToggleButton>
            )}
          </ToggleButtonGroup>

          {context && startType === 'CASH_DRAWER' && (
            <Alert
              severity={context.drawerStatus === 'OPEN' ? 'info' : 'success'}
              sx={{ mb: 2 }}
            >
              <Typography variant="body2" fontWeight={600}>
                {context.drawerName} {context.drawerCode ? `#${context.drawerCode}` : ''}
              </Typography>
              {context.drawerStatus === 'OPEN' ? (
                <>
                  <Typography variant="body2">
                    {t('drawer_open_by', {
                      name: context.openedByName || t('another_employee'),
                      time: context.openedAt ? formatDateTime(context.openedAt) : '',
                    })}
                  </Typography>
                  <Typography variant="body2">
                    {context.canJoinExisting
                      ? t('you_will_join_drawer')
                      : t('drawer_open_not_shared')}
                  </Typography>
                </>
              ) : (
                <Typography variant="body2">{t('drawer_is_closed')}</Typography>
              )}
            </Alert>
          )}

          <Stack spacing={2}>
            {startType === 'CASH_DRAWER' && context?.drawerStatus === 'CLOSED' && (
              <TextField
                label={t('opening_amount_label')}
                type="number"
                value={openAmount}
                onChange={(e) => setOpenAmount(e.target.value)}
                inputProps={{ min: 0, step: 0.01 }}
                onWheel={preventNumberScroll}
                fullWidth
                autoFocus
                helperText={t('opening_amount_help')}
              />
            )}

            {startType === 'CASH_DRAWER'
              && context?.drawerStatus === 'OPEN'
              && context?.managerApprovalRequired
              && !context?.managerOrAdmin && (
                <>
                  <Alert severity="warning">{t('manager_approval_join_help')}</Alert>
                  <TextField
                    label={t('approver_username')}
                    value={approverUsername}
                    onChange={(e) => setApproverUsername(e.target.value)}
                    fullWidth
                    autoComplete="off"
                  />
                  <TextField
                    label={t('approver_password')}
                    type="password"
                    value={approverPassword}
                    onChange={(e) => setApproverPassword(e.target.value)}
                    fullWidth
                    autoComplete="off"
                  />
                </>
              )}

            {startType !== 'CASH_DRAWER' && (
              <Alert severity="info">{t('non_cash_shift_notice')}</Alert>
            )}

            <TextField
              label={t('notes_optional')}
              value={openNotes}
              onChange={(e) => setOpenNotes(e.target.value)}
              multiline
              rows={2}
              fullWidth
            />

            <Button
              variant="contained"
              size="large"
              onClick={handleOpenShift}
              disabled={openMutation.isPending
                || (startType === 'CASH_DRAWER' && context?.drawerStatus === 'CLOSED'
                  && (!openAmount || parseFloat(openAmount) < 0))
                || (startType === 'CASH_DRAWER' && context && !context.canOpenCashDrawer)}
            >
              {openMutation.isPending
                ? t('opening')
                : (startType === 'CASH_DRAWER'
                  ? t('open_cash_shift')
                  : startType === 'SUPERVISOR' ? t('open_supervisor_shift') : t('open_non_cash_shift'))}
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
                  {t(SHIFT_TYPE_KEYS[currentShift.shiftType] || 'shift_type_cash_drawer')}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {t('opened_at', { time: formatDateTime(currentShift.openingTime) })}
                </Typography>
                {isCashShift && currentShift.drawerName && (
                  <Typography variant="body2" color="text.secondary">
                    {currentShift.drawerName}
                    {currentShift.drawerCode ? ` #${currentShift.drawerCode}` : ''}
                    {' · '}
                    <Chip
                      size="small"
                      label={drawerSessionOpen ? t('drawer_status_open') : t('drawer_status_closed')}
                      color={drawerSessionOpen ? 'info' : 'default'}
                    />
                  </Typography>
                )}
                {!isCashShift && (
                  <Typography variant="body2" color="warning.dark" sx={{ mt: 0.5 }}>
                    {t('non_cash_shift_banner')}
                  </Typography>
                )}
              </Box>
              <Stack direction="row" spacing={1} flexWrap="wrap">
                {canUseDrawerOperations && (
                  <Button variant="outlined" onClick={() => setOperationOpen(true)}>
                    {t('drawer_operations')}
                  </Button>
                )}
                <Button variant="contained" color="warning" onClick={openCloseDialog}>
                  {t('close_shift')}
                </Button>
              </Stack>
            </Stack>
          </Paper>

          <Grid container spacing={3} sx={{ mb: 3 }}>
            <Grid item xs={12} sm={6} md={3}>
              <Paper sx={{ p: 2, textAlign: 'center' }}>
                <Typography variant="body2" color="text.secondary">{t('gross_sales')}</Typography>
                <Typography variant="h5" fontWeight="bold">{formatCurrency(currentShift.grossSales)}</Typography>
              </Paper>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Paper sx={{ p: 2, textAlign: 'center' }}>
                <Typography variant="body2" color="text.secondary">{t('net_sales')}</Typography>
                <Typography variant="h5" fontWeight="bold">{formatCurrency(currentShift.netSales)}</Typography>
              </Paper>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Paper sx={{ p: 2, textAlign: 'center' }}>
                <Typography variant="body2" color="text.secondary">
                  {isCashShift ? t('cash_sales_this_shift') : t('non_cash_sales')}
                </Typography>
                <Typography variant="h5" fontWeight="bold">
                  {formatCurrency(isCashShift ? cashSales : nonCashSales)}
                </Typography>
              </Paper>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Paper sx={{ p: 2, textAlign: 'center' }}>
                <Typography variant="body2" color="text.secondary">{t('transactions')}</Typography>
                <Typography variant="h5" fontWeight="bold">{currentShift.transactionCount || 0}</Typography>
              </Paper>
            </Grid>
          </Grid>

          {isCashShift ? (
            <Paper sx={{ p: 3, mb: 3 }}>
              <Typography variant="subtitle1" fontWeight={600} gutterBottom>
                {t('cash_drawer_summary')}
              </Typography>
              <Grid container spacing={2}>
                <Grid item xs={12} sm={4}>
                  <Typography variant="body2" color="text.secondary">{t('opening_amount')}</Typography>
                  <Typography variant="h6">{formatCurrency(openingAmount)}</Typography>
                </Grid>
                <Grid item xs={12} sm={4}>
                  <Typography variant="body2" color="text.secondary">{t('cash_refunds')}</Typography>
                  <Typography variant="h6">{formatCurrency(-returnsTotal)}</Typography>
                </Grid>
                <Grid item xs={12} sm={4}>
                  <Typography variant="body2" color="text.secondary">{t('expected_drawer')}</Typography>
                  {currentShift.expectedCashHidden ? (
                    <Typography variant="body2" color="text.secondary" sx={{ fontStyle: 'italic' }}>
                      {t('expected_hidden_until_count')}
                    </Typography>
                  ) : expectedAmount != null ? (
                    <Typography variant="h6">{formatCurrency(expectedAmount)}</Typography>
                  ) : (
                    <Typography variant="body2" color="text.secondary" sx={{ fontStyle: 'italic' }}>
                      {t('expected_managed_by_owner', {
                        name: currentShift.drawerOwnerName || t('drawer_owner'),
                      })}
                    </Typography>
                  )}
                </Grid>
              </Grid>
              {currentShift.drawerOwnerName && !isDrawerOwner && (
                <Alert severity="info" sx={{ mt: 2 }}>
                  {t('shared_drawer_note', { name: currentShift.drawerOwnerName })}
                </Alert>
              )}
              {currentShift.variance != null && (
                <Alert
                  severity={exceedsTolerance(Number(currentShift.variance)) ? 'warning' : 'success'}
                  sx={{ mt: 2 }}
                >
                  {t('drawer_variance_closed', { variance: formatCurrency(currentShift.variance) })}
                </Alert>
              )}
            </Paper>
          ) : (
            <Paper sx={{ p: 3, mb: 3 }}>
              <Typography variant="subtitle1" fontWeight={600} gutterBottom>
                {t('non_cash_summary')}
              </Typography>
              <Grid container spacing={2}>
                <Grid item xs={12} sm={4}>
                  <Typography variant="body2" color="text.secondary">{t('refunds')}</Typography>
                  <Typography variant="h6">{formatCurrency(returnsTotal)}</Typography>
                </Grid>
                <Grid item xs={12} sm={4}>
                  <Typography variant="body2" color="text.secondary">{t('non_cash_sales')}</Typography>
                  <Typography variant="h6">{formatCurrency(nonCashSales)}</Typography>
                </Grid>
                <Grid item xs={12} sm={4}>
                  <Typography variant="body2" color="text.secondary">{t('cash_drawer')}</Typography>
                  <Typography variant="body2" color="text.secondary" sx={{ fontStyle: 'italic' }}>
                    {t('no_drawer_attached')}
                  </Typography>
                </Grid>
              </Grid>
            </Paper>
          )}

          {breakdownEntries.length > 0 && (
            <Paper sx={{ p: 3, mb: 3 }}>
              <Typography variant="subtitle1" fontWeight={600} gutterBottom>
                {t('payment_breakdown')}
              </Typography>
              <Table size="small">
                <TableBody>
                  {breakdownEntries.map(([method, amount]) => (
                    <TableRow key={method}>
                      <TableCell>{t(`payment_method_${method}`, method)}</TableCell>
                      <TableCell align="right">{formatCurrency(amount)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Paper>
          )}
        </>
      )}

      {/* Close shift dialog */}
      <Dialog open={closeDialogOpen} onClose={() => setCloseDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>
          {isCashShift ? t('close_shift') : t('close_non_cash_shift')}
        </DialogTitle>
        <DialogContent>
          {!isCashShift ? (
            <>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                {t('non_cash_close_help')}
              </Typography>
              <Stack spacing={2}>
                <Paper variant="outlined" sx={{ p: 2 }}>
                  <Stack direction="row" justifyContent="space-between">
                    <Typography>{t('transactions')}</Typography>
                    <Typography fontWeight={600}>{currentShift?.transactionCount || 0}</Typography>
                  </Stack>
                  <Stack direction="row" justifyContent="space-between">
                    <Typography>{t('gross_sales')}</Typography>
                    <Typography>{formatCurrency(currentShift?.grossSales)}</Typography>
                  </Stack>
                  <Stack direction="row" justifyContent="space-between">
                    <Typography>{t('refunds')}</Typography>
                    <Typography>{formatCurrency(currentShift?.returnsTotal)}</Typography>
                  </Stack>
                  <Divider sx={{ my: 1 }} />
                  <Stack direction="row" justifyContent="space-between">
                    <Typography fontWeight={600}>{t('net_sales')}</Typography>
                    <Typography fontWeight={600}>{formatCurrency(currentShift?.netSales)}</Typography>
                  </Stack>
                </Paper>
                <TextField
                  label={t('notes_optional')}
                  value={closeNotes}
                  onChange={(e) => setCloseNotes(e.target.value)}
                  multiline
                  rows={2}
                  fullWidth
                />
              </Stack>
            </>
          ) : (
            <Stack spacing={2}>
              {canCloseDrawer && (
                <FormControlLabel
                  control={(
                    <Checkbox
                      checked={closeDrawer}
                      onChange={(e) => setCloseDrawer(e.target.checked)}
                    />
                  )}
                  label={t('close_drawer_too')}
                />
              )}
              {canCloseDrawer && closeDrawer ? (
                <>
                  <Alert severity="info">{t('blind_close_help')}</Alert>
                  <TextField
                    label={t('actual_cash_in_drawer_label')}
                    type="number"
                    value={closeCounted}
                    onChange={(e) => setCloseCounted(e.target.value)}
                    inputProps={{ min: 0, step: 0.01 }}
                    onWheel={preventNumberScroll}
                    fullWidth
                    autoFocus
                  />
                </>
              ) : (
                <Alert severity="info">
                  {isDrawerOwner
                    ? t('close_shift_only_help')
                    : t('shared_drawer_close_help', {
                      name: currentShift?.drawerOwnerName || t('drawer_owner'),
                    })}
                </Alert>
              )}

              <TextField
                label={t('notes_optional')}
                value={closeNotes}
                onChange={(e) => setCloseNotes(e.target.value)}
                multiline
                rows={2}
                fullWidth
              />

              {isManager() && (
                <>
                  <FormControlLabel
                    control={(
                      <Checkbox
                        checked={forceClose}
                        onChange={(e) => setForceClose(e.target.checked)}
                      />
                    )}
                    label={t('force_close')}
                  />
                  {forceClose && (
                    <TextField
                      label={t('force_close_reason')}
                      value={forceReason}
                      onChange={(e) => setForceReason(e.target.value)}
                      fullWidth
                      required
                    />
                  )}
                </>
              )}
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCloseDialogOpen(false)}>{t('cancel')}</Button>
          <Button
            variant="contained"
            color={isCashShift && closeDrawer && canCloseDrawer ? 'warning' : 'primary'}
            onClick={handleCloseShift}
            disabled={closeMutation.isPending
              || (isCashShift && canCloseDrawer && closeDrawer && closeCounted === '')
              || (forceClose && !forceReason.trim())}
          >
            {closeMutation.isPending ? t('closing') : t('close_shift')}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Drawer operations dialog */}
      <Dialog open={operationOpen} onClose={() => setOperationOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle>{t('drawer_operations')}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              select
              label={t('operation_type')}
              value={operationType}
              onChange={(e) => setOperationType(e.target.value)}
              fullWidth
            >
              <MenuItem value="SAFE_DROP">{t('operation_safe_drop')}</MenuItem>
              <MenuItem value="PAY_IN">{t('operation_pay_in')}</MenuItem>
              <MenuItem value="PAY_OUT">{t('operation_pay_out')}</MenuItem>
              <MenuItem value="NO_SALE_OPEN">{t('operation_no_sale_open')}</MenuItem>
            </TextField>

            {operationType !== 'NO_SALE_OPEN' && (
              <TextField
                label={t('amount')}
                type="number"
                value={operationAmount}
                onChange={(e) => setOperationAmount(e.target.value)}
                inputProps={{ min: 0, step: 0.01 }}
                onWheel={preventNumberScroll}
                fullWidth
                autoFocus
              />
            )}

            <TextField
              label={operationType === 'PAY_OUT' ? t('reason_required') : t('reason_optional')}
              value={operationReason}
              onChange={(e) => setOperationReason(e.target.value)}
              multiline
              rows={2}
              fullWidth
            />

            <Alert severity="info">
              {operationType === 'SAFE_DROP' && t('safe_drop_help')}
              {operationType === 'PAY_IN' && t('pay_in_help')}
              {operationType === 'PAY_OUT' && t('pay_out_help')}
              {operationType === 'NO_SALE_OPEN' && t('no_sale_open_help')}
            </Alert>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOperationOpen(false)}>{t('cancel')}</Button>
          <Button
            variant="contained"
            onClick={handleDrawerOperation}
            disabled={drawerOperationMutation.isPending
              || (operationType !== 'NO_SALE_OPEN'
                && (!operationAmount || parseFloat(operationAmount) <= 0))
              || (operationType === 'PAY_OUT' && !operationReason.trim())}
          >
            {drawerOperationMutation.isPending ? t('saving') : t('record_operation')}
          </Button>
        </DialogActions>
      </Dialog>

      {isManager() && (
        <Box sx={{ mt: 4 }}>
          <Typography variant="h6" fontWeight={600} gutterBottom>
            {t('shift_history')}
          </Typography>
          <ShiftHistory />
        </Box>
      )}
    </Box>
  );
};

export default CashShift;
