import { useState } from 'react';
import { Box, Typography, Paper, TextField, Button, Stack, Table, TableBody, TableCell, TableHead, TableRow, TableContainer, Dialog, DialogTitle, DialogContent, DialogActions, Alert, Chip, FormControlLabel, Checkbox, Divider } from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { accountingService } from '../api/services';
import { formatCurrency, formatDateTime } from '../utils/helpers';
import { notifySuccess, notifyError } from '../utils/notify';

const PeriodControl = () => {
  const { t } = useTranslation('accounting');
  const queryClient = useQueryClient();

  const [closeTarget, setCloseTarget] = useState('');
  const [reopenTarget, setReopenTarget] = useState(null);
  const [reopenReason, setReopenReason] = useState('');
  const [backupConfirmed, setBackupConfirmed] = useState(false);
  const [profitRecon, setProfitRecon] = useState(null);
  const [costRecon, setCostRecon] = useState(null);
  const [reconLoading, setReconLoading] = useState(false);

  const { data: periodsData } = useQuery({
    queryKey: ['accountingPeriods'],
    queryFn: () => accountingService.getPeriods(),
  });
  const periods = periodsData?.data || [];

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['accountingPeriods'] });

  const closeMutation = useMutation({
    mutationFn: (period) => accountingService.closePeriod(period),
    onSuccess: () => { invalidate(); notifySuccess(t('period_closed')); setCloseTarget(''); },
    onError: (err) => notifyError(err.friendlyMessage || err.message),
  });

  const reopenMutation = useMutation({
    mutationFn: ({ period, reason }) => accountingService.reopenPeriod(period, reason),
    onSuccess: () => { invalidate(); notifySuccess(t('period_reopened')); setReopenTarget(null); setReopenReason(''); },
    onError: (err) => notifyError(err.friendlyMessage || err.message),
  });

  const cutoverMutation = useMutation({
    mutationFn: () => accountingService.activateLedgerReads(),
    onSuccess: () => notifySuccess(t('ledger_activated')),
    onError: (err) => notifyError(err.friendlyMessage || err.message),
  });

  const backfillMutation = useMutation({
    mutationFn: (kind) => (kind === 'profit'
      ? accountingService.backfillProfit(backupConfirmed)
      : accountingService.backfillInventory(backupConfirmed)),
    onSuccess: (response) => {
      const result = response?.data || {};
      if (result.blocked) notifyError(t('backfill_blocked'));
      else notifySuccess(`${t('backfill_done')}: ${result.posted ?? 0}`);
    },
    onError: (err) => notifyError(err.friendlyMessage || err.message),
  });

  const runReconciliation = async (kind) => {
    setReconLoading(true);
    try {
      const response = kind === 'profit'
        ? await accountingService.getReconciliation()
        : await accountingService.getCostReconciliation();
      if (kind === 'profit') setProfitRecon(response?.data || null);
      else setCostRecon(response?.data || null);
    } catch (err) {
      notifyError(err.friendlyMessage || err.message);
    } finally {
      setReconLoading(false);
    }
  };

  const reconSummary = (label, result) => result && (
    <Box sx={{ mt: 1 }}>
      <Stack direction="row" spacing={1} alignItems="center">
        <Typography variant="body2">{label}</Typography>
        <Chip size="small" color={result.reconciled ? 'success' : 'error'}
          label={result.reconciled ? t('reconciled') : t('not_reconciled')} />
        {result.missingPostings != null && (
          <Typography variant="caption">{t('missing_postings')}: {result.missingPostings}</Typography>
        )}
      </Stack>
      {result.settlementReconciliation && (
        <Box sx={{ mt: 1 }}>
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
            <Typography variant="caption">{t('settlement_reconciliation')}</Typography>
            <Chip size="small" color={result.settlementReconciliation.reconciled ? 'success' : 'error'}
              label={result.settlementReconciliation.reconciled ? t('reconciled') : t('not_reconciled')} />
            <Typography variant="caption">
              {t('settlement_expected')}: {formatCurrency(result.settlementReconciliation.expected || 0)}
              {' / '}{t('settlement_actual')}: {formatCurrency(result.settlementReconciliation.actual || 0)}
            </Typography>
            {Object.entries(result.settlementReconciliation.byAccount || {}).map(([account, amount]) => (
              <Typography key={account} variant="caption">
                {account}: {formatCurrency(amount)}
              </Typography>
            ))}
          </Stack>
        </Box>
      )}
      {(result.issues || []).length > 0 && (
        <Alert severity="warning" sx={{ mt: 1 }}>
          {(result.issues || []).slice(0, 10).map((issue, i) => (
            <Typography key={i} variant="caption" display="block">
              {issue.productId != null ? `#${issue.productId}: ` : ''}{issue.reason || JSON.stringify(issue)}
            </Typography>
          ))}
          {(result.issues || []).length > 10 && (
            <Typography variant="caption">…{result.issues.length - 10} more</Typography>
          )}
        </Alert>
      )}
    </Box>
  );

  return (
    <Paper sx={{ p: 2, mb: 3 }}>
      <Typography variant="h6" sx={{ mb: 2 }}>{t('period_control')}</Typography>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }} sx={{ mb: 2 }}>
        <TextField size="small" type="month" label={t('period_label')} value={closeTarget}
          onChange={(e) => setCloseTarget(e.target.value)} InputLabelProps={{ shrink: true }} />
        <Button variant="outlined" color="warning" disabled={!closeTarget || closeMutation.isPending}
          onClick={() => closeMutation.mutate(closeTarget)}>
          {t('close_period')}
        </Button>
        <Button variant="outlined" disabled={cutoverMutation.isPending}
          onClick={() => { if (window.confirm(t('cutover_confirm'))) cutoverMutation.mutate(); }}>
          {t('activate_ledger_reads')}
        </Button>
      </Stack>

      <TableContainer sx={{ mb: 2 }}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>{t('period_label')}</TableCell>
              <TableCell>{t('closed_at')}</TableCell>
              <TableCell align="right">{t('frozen_revenue')}</TableCell>
              <TableCell align="right">{t('frozen_cogs')}</TableCell>
              <TableCell align="right">{t('frozen_expense')}</TableCell>
              <TableCell align="right" />
            </TableRow>
          </TableHead>
          <TableBody>
            {periods.length === 0 ? (
              <TableRow><TableCell colSpan={6}>{t('no_closed_periods')}</TableCell></TableRow>
            ) : periods.map((p) => (
              <TableRow key={p.period}>
                <TableCell>{p.period}</TableCell>
                <TableCell>{formatDateTime(p.closedAt)}</TableCell>
                <TableCell align="right">{formatCurrency(p.frozenRevenue)}</TableCell>
                <TableCell align="right">{formatCurrency(p.frozenCogs)}</TableCell>
                <TableCell align="right">{formatCurrency(p.frozenExpense)}</TableCell>
                <TableCell align="right">
                  <Button size="small" onClick={() => { setReopenTarget(p.period); setReopenReason(''); }}>
                    {t('reopen')}
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      <Divider sx={{ my: 2 }} />

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }}>
        <Button variant="outlined" disabled={reconLoading} onClick={() => runReconciliation('profit')}>
          {t('profit_reconciliation')}
        </Button>
        <Button variant="outlined" disabled={reconLoading} onClick={() => runReconciliation('cost')}>
          {t('cost_reconciliation')}
        </Button>
        <FormControlLabel control={<Checkbox checked={backupConfirmed} onChange={(e) => setBackupConfirmed(e.target.checked)} />}
          label={t('backup_confirmed')} />
        <Button variant="outlined" color="secondary" disabled={!backupConfirmed || backfillMutation.isPending}
          onClick={() => backfillMutation.mutate('profit')}>
          {t('backfill_profit')}
        </Button>
        <Button variant="outlined" color="secondary" disabled={!backupConfirmed || backfillMutation.isPending}
          onClick={() => backfillMutation.mutate('inventory')}>
          {t('backfill_inventory')}
        </Button>
      </Stack>
      {reconSummary(t('profit_reconciliation'), profitRecon)}
      {reconSummary(t('cost_reconciliation'), costRecon)}

      <Dialog open={!!reopenTarget} onClose={() => setReopenTarget(null)} maxWidth="xs" fullWidth>
        <DialogTitle>{t('reopen_period')} — {reopenTarget}</DialogTitle>
        <DialogContent>
          <TextField fullWidth multiline rows={3} label={t('reopen_reason')} value={reopenReason}
            onChange={(e) => setReopenReason(e.target.value)} sx={{ mt: 1 }} required />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setReopenTarget(null)}>{t('cancel')}</Button>
          <Button variant="contained" color="warning" disabled={!reopenReason.trim() || reopenMutation.isPending}
            onClick={() => reopenMutation.mutate({ period: reopenTarget, reason: reopenReason.trim() })}>
            {t('reopen')}
          </Button>
        </DialogActions>
      </Dialog>
    </Paper>
  );
};

export default PeriodControl;