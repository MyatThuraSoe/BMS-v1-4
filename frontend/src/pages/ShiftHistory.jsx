import { useState } from 'react';
import {
  Box, Typography, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Chip, TextField, MenuItem, TablePagination,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { shiftService, userService } from '../api/services';

const formatCurrency = (amount) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount || 0);

const ShiftHistory = () => {
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [cashierId, setCashierId] = useState('');

  const { data: usersData } = useQuery({
    queryKey: ['users-for-shift-filter'],
    queryFn: () => userService.getAll(0, 100),
  });
  const cashiers = usersData?.data?.content || [];

  const { data: shiftsData, isLoading } = useQuery({
    queryKey: ['shift-history', page, size, cashierId],
    queryFn: () => shiftService.getShiftHistory({ page, size, cashierId: cashierId || undefined }),
    refetchOnMount: 'always', // 👈 Forces a fresh call to the server every time you open this page
  });
  const shifts = shiftsData?.data?.content || [];
  const totalElements = shiftsData?.data?.page?.totalElements || 0;

  return (
    <Box>
      <Typography variant="h4" gutterBottom>Shift History</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Past cash drawer shifts across all cashiers.
      </Typography>

      <Paper sx={{ p: 2, mb: 2 }}>
        <TextField
          select
          size="small"
          label="Filter by Cashier"
          value={cashierId}
          onChange={(e) => { setCashierId(e.target.value); setPage(0); }}
          sx={{ minWidth: 220 }}
        >
          <MenuItem value="">All Cashiers</MenuItem>
          {cashiers.map((c) => (
            <MenuItem key={c.id} value={c.id}>{c.firstName} {c.lastName}</MenuItem>
          ))}
        </TextField>
      </Paper>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Cashier</TableCell>
              <TableCell>Opened</TableCell>
              <TableCell>Closed</TableCell>
              <TableCell align="right">Opening Amount</TableCell>
              <TableCell align="right">Expected</TableCell>
              <TableCell align="right">Actual</TableCell>
              <TableCell align="right">Variance</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={8}>Loading...</TableCell></TableRow>
            ) : shifts.length === 0 ? (
              <TableRow><TableCell colSpan={8}>No shifts found.</TableCell></TableRow>
            ) : (
              shifts.map((s) => (
                <TableRow key={s.id}>
                  <TableCell>{s.cashierName || s.cashierId}</TableCell>
                  <TableCell>{new Date(s.openingTime).toLocaleString()}</TableCell>
                  <TableCell>{s.closingTime ? new Date(s.closingTime).toLocaleString() : '-'}</TableCell>
                  <TableCell align="right">{formatCurrency(s.openingAmount)}</TableCell>
                  <TableCell align="right">{s.expectedAmount != null ? formatCurrency(s.expectedAmount) : '-'}</TableCell>
                  <TableCell align="right">{s.closingAmount != null ? formatCurrency(s.closingAmount) : '-'}</TableCell>
                  <TableCell align="right">
                    {s.variance != null ? (
                      <Chip
                        size="small"
                        label={formatCurrency(s.variance)}
                        color={Math.abs(s.variance) < 0.01 ? 'success' : Math.abs(s.variance) < 5 ? 'warning' : 'error'}
                      />
                    ) : '-'}
                  </TableCell>
                  <TableCell>
                    <Chip size="small" label={s.status} color={s.status === 'OPEN' ? 'info' : 'default'} />
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
        <TablePagination
          component="div"
          count={totalElements}
          page={page}
          rowsPerPage={size}
          onPageChange={(e, newPage) => setPage(newPage)}
          rowsPerPageOptions={[size]}
        />
      </TableContainer>
    </Box>
  );
};

export default ShiftHistory;