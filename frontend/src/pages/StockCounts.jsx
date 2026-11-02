import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  MenuItem, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead,
  TablePagination, TableRow, TextField, Typography,
} from '@mui/material';
import {
  Add as AddIcon, Autorenew as GenerateIcon, PlaylistAddCheck as AbcIcon,
} from '@mui/icons-material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { stockCountService } from '../api/services';
import { formatCurrency, formatDate } from '../utils/helpers';
import { notifyError, notifySuccess } from '../utils/notify';

const STATUS_COLOR = {
  DRAFT: 'default',
  IN_PROGRESS: 'info',
  PENDING_REVIEW: 'warning',
  APPROVED: 'success',
  CANCELLED: 'error',
};

const COUNT_TYPES = ['MANUAL', 'FULL', 'ABC_A', 'ABC_B', 'ABC_C'];

const StockCounts = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState({ countType: 'MANUAL', notes: '' });

  const { data, isLoading, error } = useQuery({
    queryKey: ['stock-counts', status, page, size],
    queryFn: () => stockCountService.getAll({ status, page, size }),
  });

  const sheets = data?.data?.content || [];
  const total = data?.data?.page?.totalElements ?? data?.data?.totalElements ?? sheets.length;

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['stock-counts'] });

  const generateMutation = useMutation({
    mutationFn: stockCountService.generateDue,
    onSuccess: (res) => {
      invalidate();
      const created = res?.data?.length || 0;
      notifySuccess(created ? `${created} count sheet(s) generated` : 'No counts are due right now');
    },
    onError: (err) => notifyError(err.friendlyMessage || 'Failed to generate count sheets'),
  });

  const abcMutation = useMutation({
    mutationFn: stockCountService.refreshAbc,
    onSuccess: (res) => notifySuccess(`${res?.data?.productsReclassified ?? 0} product(s) reclassified`),
    onError: (err) => notifyError(err.friendlyMessage || 'Failed to refresh ABC classes'),
  });

  const createMutation = useMutation({
    mutationFn: (payload) => stockCountService.create(payload),
    onSuccess: (res) => {
      invalidate();
      setCreateOpen(false);
      setForm({ countType: 'MANUAL', notes: '' });
      notifySuccess('Count sheet created');
      const id = res?.data?.id;
      if (id) navigate(`/stock-counts/${id}`);
    },
    onError: (err) => notifyError(err.friendlyMessage || 'Failed to create count sheet'),
  });

  return (
    <Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={2} sx={{ mb: 2 }}>
        <Box>
          <Typography variant="h5" fontWeight={600}>Stock Counts</Typography>
          <Typography variant="body2" color="text.secondary">
            ABC cycle counts, blind counting and variance approval
          </Typography>
        </Box>
        <Stack direction="row" spacing={1} flexWrap="wrap">
          <Button variant="outlined" startIcon={<AbcIcon />} onClick={() => abcMutation.mutate()} disabled={abcMutation.isPending}>
            Refresh ABC
          </Button>
          <Button variant="outlined" startIcon={<GenerateIcon />} onClick={() => generateMutation.mutate()} disabled={generateMutation.isPending}>
            Generate due
          </Button>
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreateOpen(true)}>
            New count
          </Button>
        </Stack>
      </Stack>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error.friendlyMessage || 'Failed to load count sheets'}</Alert>}

      <Paper sx={{ p: 2 }}>
        <Stack direction="row" spacing={2} sx={{ mb: 2 }}>
          <TextField
            select
            size="small"
            label="Status"
            value={status}
            onChange={(e) => { setStatus(e.target.value); setPage(0); }}
            sx={{ minWidth: 180 }}
          >
            <MenuItem value="">All statuses</MenuItem>
            {Object.keys(STATUS_COLOR).map((s) => (
              <MenuItem key={s} value={s}>{s.replace('_', ' ')}</MenuItem>
            ))}
          </TextField>
        </Stack>

        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Sheet</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Scheduled</TableCell>
                <TableCell>Status</TableCell>
                <TableCell align="right">Items</TableCell>
                <TableCell align="right">Variance value</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {isLoading ? (
                <TableRow><TableCell colSpan={7}>Loading...</TableCell></TableRow>
              ) : sheets.length === 0 ? (
                <TableRow><TableCell colSpan={7}>No count sheets found.</TableCell></TableRow>
              ) : sheets.map((sheet) => (
                <TableRow key={sheet.id} hover>
                  <TableCell>{sheet.sheetNumber}</TableCell>
                  <TableCell>{sheet.countType?.replace('_', ' ')}</TableCell>
                  <TableCell>{formatDate(sheet.scheduledDate)}</TableCell>
                  <TableCell>
                    <Chip size="small" label={sheet.status?.replace('_', ' ')} color={STATUS_COLOR[sheet.status] || 'default'} />
                  </TableCell>
                  <TableCell align="right">{sheet.itemCount}</TableCell>
                  <TableCell align="right">{formatCurrency(sheet.varianceValue || 0)}</TableCell>
                  <TableCell align="right">
                    <Button size="small" onClick={() => navigate(`/stock-counts/${sheet.id}`)}>Open</Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
        <TablePagination
          component="div"
          count={total}
          page={page}
          onPageChange={(e, next) => setPage(next)}
          rowsPerPage={size}
          onRowsPerPageChange={(e) => { setSize(Number(e.target.value)); setPage(0); }}
          rowsPerPageOptions={[10, 20, 50, 100]}
        />
      </Paper>

      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm">
        <DialogTitle>New count sheet</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <TextField
              select
              label="Count type"
              value={form.countType}
              onChange={(e) => setForm({ ...form, countType: e.target.value })}
            >
              {COUNT_TYPES.map((type) => <MenuItem key={type} value={type}>{type.replace('_', ' ')}</MenuItem>)}
            </TextField>
            <TextField
              label="Notes"
              multiline
              minRows={2}
              value={form.notes}
              onChange={(e) => setForm({ ...form, notes: e.target.value })}
            />
            <Alert severity="info">
              Leaving the count type as MANUAL without a product selection creates a full count of every active product.
            </Alert>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreateOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={() => createMutation.mutate(form)} disabled={createMutation.isPending}>
            Create
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default StockCounts;
