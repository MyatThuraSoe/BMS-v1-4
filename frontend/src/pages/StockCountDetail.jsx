import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  FormControlLabel, MenuItem, Paper, Stack, Switch, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import { ArrowBack as BackIcon, PlayArrow as StartIcon } from '@mui/icons-material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { stockCountService } from '../api/services';
import { formatCurrency, formatDateTime } from '../utils/helpers';
import { notifyError, notifySuccess } from '../utils/notify';

const STATUS_COLOR = {
  DRAFT: 'default',
  IN_PROGRESS: 'info',
  PENDING_REVIEW: 'warning',
  APPROVED: 'success',
  CANCELLED: 'error',
};

const DEFAULT_REASON_CODES = [
  'SPOILAGE_EXPIRY', 'THEFT_SHRINKAGE', 'DAMAGED_IN_STORE',
  'INTERNAL_CONSUMPTION', 'COUNT_CORRECTION',
  'DAMAGED', 'THEFT', 'EXPIRED', 'MISCOUNT', 'SUPPLIER_ERROR', 'RECALL', 'OTHER',
];

const StockCountDetail = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [blind, setBlind] = useState(false);
  const [counts, setCounts] = useState({});
  const [reasons, setReasons] = useState({});
  const [notes, setNotes] = useState('');
  const [rejectOpen, setRejectOpen] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [error, setError] = useState('');

  const { data, isLoading, refetch } = useQuery({
    queryKey: ['stock-count', id, blind],
    queryFn: () => stockCountService.getById(id, { blind }),
  });

  const { data: reasonData } = useQuery({
    queryKey: ['stock-count-reason-codes'],
    queryFn: stockCountService.getReasonCodes,
  });

  const sheet = data?.data;
  const reasonCodes = reasonData?.data?.length ? reasonData.data : DEFAULT_REASON_CODES;

  useEffect(() => {
    if (!sheet) return;
    const nextCounts = {};
    const nextReasons = {};
    (sheet.items || []).forEach((item) => {
      nextCounts[item.id] = item.countedQuantity ?? '';
      nextReasons[item.id] = item.reasonCode || '';
    });
    setCounts(nextCounts);
    setReasons(nextReasons);
    setNotes(sheet.notes || '');
  }, [sheet?.id, sheet?.status]); // eslint-disable-line react-hooks/exhaustive-deps

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['stock-count', id] });
    queryClient.invalidateQueries({ queryKey: ['stock-counts'] });
    queryClient.invalidateQueries({ queryKey: ['products'] });
    queryClient.invalidateQueries({ queryKey: ['inventory'] });
  };

  const startMutation = useMutation({
    mutationFn: () => stockCountService.start(id),
    onSuccess: () => { invalidate(); notifySuccess('Count sheet started'); },
    onError: (err) => notifyError(err.friendlyMessage || 'Failed to start count'),
  });

  const submitMutation = useMutation({
    mutationFn: () => stockCountService.submit(id, {
      notes,
      items: (sheet?.items || []).map((item) => ({
        itemId: item.id,
        countedQuantity: Number(counts[item.id]),
        reasonCode: reasons[item.id] || null,
      })),
    }),
    onSuccess: (res) => {
      invalidate();
      const status = res?.data?.status;
      notifySuccess(status === 'APPROVED' ? 'Count submitted and auto-approved' : 'Count submitted for review');
      refetch();
    },
    onError: (err) => setError(err.friendlyMessage || err.response?.data?.message || 'Failed to submit count'),
  });

  const approveMutation = useMutation({
    mutationFn: () => stockCountService.approve(id),
    onSuccess: () => { invalidate(); notifySuccess('Count approved and stock adjusted'); refetch(); },
    onError: (err) => notifyError(err.friendlyMessage || 'Failed to approve count'),
  });

  const rejectMutation = useMutation({
    mutationFn: () => stockCountService.reject(id, rejectReason),
    onSuccess: () => {
      invalidate();
      setRejectOpen(false);
      notifySuccess('Count sheet rejected');
      refetch();
    },
    onError: (err) => notifyError(err.friendlyMessage || 'Failed to reject count'),
  });

  const totalVariance = useMemo(() => {
    if (!sheet?.items) return 0;
    return sheet.items.reduce((sum, item) => sum + (Number(item.variance) || 0), 0);
  }, [sheet]);

  if (isLoading) return <Typography>Loading...</Typography>;
  if (!sheet) return <Alert severity="error">Count sheet not found.</Alert>;

  const editable = sheet.status === 'IN_PROGRESS';
  const allCounted = (sheet.items || []).every((item) => counts[item.id] !== '' && counts[item.id] != null);

  return (
    <Box>
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 2 }}>
        <Button startIcon={<BackIcon />} onClick={() => navigate('/stock-counts')}>Back</Button>
        <Box sx={{ flexGrow: 1 }}>
          <Typography variant="h5" fontWeight={600}>{sheet.sheetNumber}</Typography>
          <Typography variant="body2" color="text.secondary">
            {sheet.countType?.replace('_', ' ')} &middot; scheduled {sheet.scheduledDate}
          </Typography>
        </Box>
        <Chip label={sheet.status?.replace('_', ' ')} color={STATUS_COLOR[sheet.status] || 'default'} />
      </Stack>

      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError('')}>{error}</Alert>}

      <Paper sx={{ p: 2, mb: 2 }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={3}>
          <Box><Typography variant="caption" color="text.secondary">Items</Typography><Typography>{sheet.itemCount}</Typography></Box>
          <Box><Typography variant="caption" color="text.secondary">Variance value</Typography><Typography>{formatCurrency(sheet.varianceValue || 0)}</Typography></Box>
          <Box><Typography variant="caption" color="text.secondary">Net units</Typography><Typography>{totalVariance > 0 ? '+' : ''}{totalVariance}</Typography></Box>
          <Box><Typography variant="caption" color="text.secondary">Started</Typography><Typography>{sheet.startedAt ? formatDateTime(sheet.startedAt) : '—'}</Typography></Box>
          <Box><Typography variant="caption" color="text.secondary">Submitted</Typography><Typography>{sheet.submittedAt ? formatDateTime(sheet.submittedAt) : '—'}</Typography></Box>
        </Stack>
        {sheet.notes && <Alert severity="info" sx={{ mt: 2 }}>{sheet.notes}</Alert>}
      </Paper>

      <Paper sx={{ p: 2 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={2} sx={{ mb: 2 }}>
          <Stack direction="row" spacing={2} alignItems="center">
            <FormControlLabel
              control={<Switch checked={blind} onChange={(e) => setBlind(e.target.checked)} />}
              label="Blind count (hide expected quantities)"
            />
            {sheet.status === 'DRAFT' && (
              <Button variant="contained" startIcon={<StartIcon />} onClick={() => startMutation.mutate()} disabled={startMutation.isPending}>
                Start count
              </Button>
            )}
          </Stack>
          {editable && (
            <Button variant="contained" onClick={() => submitMutation.mutate()} disabled={!allCounted || submitMutation.isPending}>
              Submit count
            </Button>
          )}
          {sheet.status === 'PENDING_REVIEW' && (
            <Stack direction="row" spacing={1}>
              <Button color="success" variant="contained" onClick={() => approveMutation.mutate()} disabled={approveMutation.isPending}>
                Approve &amp; adjust stock
              </Button>
              <Button color="error" variant="outlined" onClick={() => setRejectOpen(true)}>Reject</Button>
            </Stack>
          )}
        </Stack>

        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Product</TableCell>
                <TableCell>SKU / Bin</TableCell>
                {!blind && <TableCell align="right">Expected</TableCell>}
                <TableCell align="right" sx={{ width: 140 }}>Counted</TableCell>
                {!blind && <TableCell align="right">Variance</TableCell>}
                <TableCell sx={{ minWidth: 190 }}>Reason code</TableCell>
                <TableCell>Note</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(sheet.items || []).map((item) => {
                const counted = counts[item.id];
                const variance = !blind && counted !== '' && counted != null
                  ? Number(counted) - Number(item.systemQuantity) : null;
                return (
                  <TableRow key={item.id}>
                    <TableCell>{item.productName}</TableCell>
                    <TableCell>{item.sku}{item.binLocation ? ` / ${item.binLocation}` : ''}</TableCell>
                    {!blind && <TableCell align="right">{item.systemQuantity}</TableCell>}
                    <TableCell align="right">
                      {editable ? (
                        <TextField
                          type="number"
                          size="small"
                          value={counted}
                          onChange={(e) => setCounts({ ...counts, [item.id]: e.target.value })}
                          inputProps={{ min: 0, style: { textAlign: 'right' } }}
                        />
                      ) : (item.countedQuantity ?? '—')}
                    </TableCell>
                    {!blind && (
                      <TableCell align="right" sx={{ color: variance ? (variance > 0 ? 'success.main' : 'error.main') : 'text.secondary' }}>
                        {variance == null ? '—' : (variance > 0 ? '+' : '') + variance}
                      </TableCell>
                    )}
                    <TableCell>
                      {editable ? (
                        <TextField
                          select
                          size="small"
                          fullWidth
                          value={reasons[item.id] || ''}
                          onChange={(e) => setReasons({ ...reasons, [item.id]: e.target.value })}
                        >
                          <MenuItem value="">No reason</MenuItem>
                          {reasonCodes.map((code) => <MenuItem key={code} value={code}>{code.replaceAll('_', ' ')}</MenuItem>)}
                        </TextField>
                      ) : (item.reasonCode ? item.reasonCode.replaceAll('_', ' ') : '—')}
                    </TableCell>
                    <TableCell>{item.note || '—'}</TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableContainer>

        {editable && (
          <TextField
            label="Count notes"
            fullWidth
            multiline
            minRows={2}
            sx={{ mt: 2 }}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
          />
        )}
      </Paper>

      <Dialog open={rejectOpen} onClose={() => setRejectOpen(false)}>
        <DialogTitle>Reject count sheet</DialogTitle>
        <DialogContent>
          <TextField
            fullWidth
            label="Reason"
            multiline
            minRows={2}
            sx={{ mt: 1 }}
            value={rejectReason}
            onChange={(e) => setRejectReason(e.target.value)}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRejectOpen(false)}>Cancel</Button>
          <Button color="error" variant="contained" onClick={() => rejectMutation.mutate()} disabled={rejectMutation.isPending}>
            Reject
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default StockCountDetail;
