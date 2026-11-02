import { useState } from 'react';
import {
  Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Grid,
  MenuItem, Paper, Stack, Tab, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import { Add as AddIcon, Download as DownloadIcon } from '@mui/icons-material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { glService } from '../api/services';
import { formatCurrency, toLocalDateString } from '../utils/helpers';
import { notifyError, notifySuccess } from '../utils/notify';

const ACCOUNT_TYPES = ['ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'];

const firstOfMonth = () => {
  const now = new Date();
  return toLocalDateString(new Date(now.getFullYear(), now.getMonth(), 1));
};

const GlExport = () => {
  const queryClient = useQueryClient();
  const [tab, setTab] = useState(0);
  const [from, setFrom] = useState(firstOfMonth());
  const [to, setTo] = useState(toLocalDateString(new Date()));
  const [accountOpen, setAccountOpen] = useState(false);
  const [accountForm, setAccountForm] = useState({ code: '', name: '', type: 'ASSET', active: true });
  const [mappingForm, setMappingForm] = useState({ eventType: '', accountId: '', description: '' });
  const [error, setError] = useState('');

  const { data: journalData, isLoading: journalLoading, error: journalError } = useQuery({
    queryKey: ['gl-journal', from, to],
    queryFn: () => glService.getJournal({ from, to }),
    enabled: tab === 0,
  });

  const { data: accountsData } = useQuery({
    queryKey: ['gl-accounts'],
    queryFn: glService.getAccounts,
  });

  const { data: mappingsData, isLoading: mappingsLoading } = useQuery({
    queryKey: ['gl-mappings'],
    queryFn: glService.getMappings,
    enabled: tab === 2,
  });

  const journal = journalData?.data || [];
  const accounts = accountsData?.data || [];
  const mappings = mappingsData?.data || [];

  const totals = journal.reduce((acc, row) => ({
    debit: acc.debit + Number(row.debit || 0),
    credit: acc.credit + Number(row.credit || 0),
  }), { debit: 0, credit: 0 });

  const createAccountMutation = useMutation({
    mutationFn: (payload) => glService.createAccount(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['gl-accounts'] });
      setAccountOpen(false);
      setAccountForm({ code: '', name: '', type: 'ASSET', active: true });
      notifySuccess('GL account created');
    },
    onError: (err) => setError(err.friendlyMessage || err.response?.data?.message || 'Failed to create account'),
  });

  const saveMappingMutation = useMutation({
    mutationFn: (payload) => glService.saveMapping(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['gl-mappings'] });
      setMappingForm({ eventType: '', accountId: '', description: '' });
      notifySuccess('GL mapping saved');
    },
    onError: (err) => notifyError(err.friendlyMessage || err.response?.data?.message || 'Failed to save mapping'),
  });

  const exportMutation = useMutation({
    mutationFn: () => glService.exportJournal({ from, to }),
    onSuccess: (response) => {
      const blob = new Blob([response.data], { type: 'text/csv;charset=utf-8;' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `journal-${from || 'start'}-${to || 'today'}.csv`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      notifySuccess('Journal exported');
    },
    onError: (err) => notifyError(err.friendlyMessage || 'Failed to export journal'),
  });

  return (
    <Box>
      <Typography variant="h5" fontWeight={600}>GL Export</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Chart of accounts, event mappings and a balanced journal for your accountant
      </Typography>

      <Tabs value={tab} onChange={(e, value) => setTab(value)} sx={{ mb: 2 }}>
        <Tab label="Journal" />
        <Tab label="Chart of accounts" />
        <Tab label="Event mappings" />
      </Tabs>

      {tab === 0 && (
        <Paper sx={{ p: 2 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }} sx={{ mb: 2 }}>
            <TextField type="date" size="small" label="From" InputLabelProps={{ shrink: true }} value={from} onChange={(e) => setFrom(e.target.value)} />
            <TextField type="date" size="small" label="To" InputLabelProps={{ shrink: true }} value={to} onChange={(e) => setTo(e.target.value)} />
            <Button variant="contained" startIcon={<DownloadIcon />} onClick={() => exportMutation.mutate()} disabled={exportMutation.isPending}>
              Export CSV
            </Button>
            <Box sx={{ flexGrow: 1 }} />
            <Typography variant="body2" color="text.secondary">
              Debits {formatCurrency(totals.debit)} · Credits {formatCurrency(totals.credit)}
            </Typography>
          </Stack>

          {journalError && <Alert severity="error" sx={{ mb: 2 }}>Failed to generate journal</Alert>}

          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Date</TableCell>
                  <TableCell>Reference</TableCell>
                  <TableCell>Description</TableCell>
                  <TableCell>Account</TableCell>
                  <TableCell align="right">Debit</TableCell>
                  <TableCell align="right">Credit</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {journalLoading ? <TableRow><TableCell colSpan={6}>Loading...</TableCell></TableRow>
                  : journal.length === 0 ? <TableRow><TableCell colSpan={6}>No journal entries in this period.</TableCell></TableRow>
                    : journal.map((row, index) => (
                      <TableRow key={`${row.reference}-${row.accountCode}-${index}`}>
                        <TableCell>{row.date}</TableCell>
                        <TableCell>{row.reference}</TableCell>
                        <TableCell>{row.description}</TableCell>
                        <TableCell>{row.accountCode} — {row.accountName}</TableCell>
                        <TableCell align="right">{Number(row.debit) ? formatCurrency(row.debit) : '—'}</TableCell>
                        <TableCell align="right">{Number(row.credit) ? formatCurrency(row.credit) : '—'}</TableCell>
                      </TableRow>
                    ))}
              </TableBody>
            </Table>
          </TableContainer>
        </Paper>
      )}

      {tab === 1 && (
        <Paper sx={{ p: 2 }}>
          <Stack direction="row" justifyContent="flex-end" sx={{ mb: 2 }}>
            <Button variant="contained" startIcon={<AddIcon />} onClick={() => setAccountOpen(true)}>New account</Button>
          </Stack>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Code</TableCell>
                  <TableCell>Name</TableCell>
                  <TableCell>Type</TableCell>
                  <TableCell>Status</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {accounts.length === 0 ? <TableRow><TableCell colSpan={4}>No accounts yet.</TableCell></TableRow>
                  : accounts.map((account) => (
                    <TableRow key={account.id}>
                      <TableCell>{account.code}</TableCell>
                      <TableCell>{account.name}</TableCell>
                      <TableCell>{account.type}</TableCell>
                      <TableCell>{account.isActive ? 'Active' : 'Inactive'}</TableCell>
                    </TableRow>
                  ))}
              </TableBody>
            </Table>
          </TableContainer>
        </Paper>
      )}

      {tab === 2 && (
        <Paper sx={{ p: 2 }}>
          <Grid container spacing={2} sx={{ mb: 2 }}>
            <Grid item xs={12} sm={4}>
              <TextField
                select
                fullWidth
                size="small"
                label="Event type"
                value={mappingForm.eventType}
                onChange={(e) => setMappingForm({ ...mappingForm, eventType: e.target.value })}
              >
                {['CASH', 'ACCOUNTS_RECEIVABLE', 'SALES_REVENUE', 'SALES_DISCOUNT', 'SALES_TAX_PAYABLE',
                  'COST_OF_GOODS_SOLD', 'INVENTORY', 'ACCOUNTS_PAYABLE', 'FREIGHT_IN', 'OPERATING_EXPENSE']
                  .map((event) => <MenuItem key={event} value={event}>{event.replaceAll('_', ' ')}</MenuItem>)}
              </TextField>
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField
                select
                fullWidth
                size="small"
                label="Account"
                value={mappingForm.accountId}
                onChange={(e) => setMappingForm({ ...mappingForm, accountId: e.target.value })}
              >
                {accounts.map((account) => (
                  <MenuItem key={account.id} value={account.id}>{account.code} — {account.name}</MenuItem>
                ))}
              </TextField>
            </Grid>
            <Grid item xs={12} sm={4}>
              <Button
                fullWidth
                variant="contained"
                sx={{ height: '100%' }}
                disabled={!mappingForm.eventType || !mappingForm.accountId || saveMappingMutation.isPending}
                onClick={() => saveMappingMutation.mutate({
                  eventType: mappingForm.eventType,
                  accountId: Number(mappingForm.accountId),
                  description: mappingForm.description || null,
                })}
              >
                Save mapping
              </Button>
            </Grid>
          </Grid>

          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Event</TableCell>
                  <TableCell>Account</TableCell>
                  <TableCell>Description</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {mappingsLoading ? <TableRow><TableCell colSpan={3}>Loading...</TableCell></TableRow>
                  : mappings.length === 0 ? <TableRow><TableCell colSpan={3}>No mappings yet.</TableCell></TableRow>
                    : mappings.map((mapping) => (
                      <TableRow key={mapping.id} hover onClick={() => setMappingForm({
                        eventType: mapping.eventType,
                        accountId: mapping.account?.id || '',
                        description: mapping.description || '',
                      })}>
                        <TableCell>{mapping.eventType?.replaceAll('_', ' ')}</TableCell>
                        <TableCell>{mapping.account?.code} — {mapping.account?.name}</TableCell>
                        <TableCell>{mapping.description || '—'}</TableCell>
                      </TableRow>
                    ))}
              </TableBody>
            </Table>
          </TableContainer>
        </Paper>
      )}

      <Dialog open={accountOpen} onClose={() => setAccountOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>New GL account</DialogTitle>
        <DialogContent>
          {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
          <Stack spacing={2} sx={{ pt: 1 }}>
            <TextField label="Code" value={accountForm.code} onChange={(e) => setAccountForm({ ...accountForm, code: e.target.value })} />
            <TextField label="Name" value={accountForm.name} onChange={(e) => setAccountForm({ ...accountForm, name: e.target.value })} />
            <TextField select label="Type" value={accountForm.type} onChange={(e) => setAccountForm({ ...accountForm, type: e.target.value })}>
              {ACCOUNT_TYPES.map((type) => <MenuItem key={type} value={type}>{type}</MenuItem>)}
            </TextField>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAccountOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={() => createAccountMutation.mutate(accountForm)} disabled={createAccountMutation.isPending}>Save</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default GlExport;
