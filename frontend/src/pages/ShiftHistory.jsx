import { useState } from 'react';
import {
  Box, Typography, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Chip, TextField, MenuItem, TablePagination,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { shiftService, userService } from '../api/services';
import { formatCurrency } from '../utils/helpers';

const ShiftHistory = () => {
  const { t } = useTranslation('cash');
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

      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        {t('shift_history_subtitle')}
      </Typography>

      <Paper sx={{ p: 2, mb: 2 }}>
        <TextField
          select
          size="small"
          label={t('filter_by_cashier')}
          value={cashierId}
          onChange={(e) => { setCashierId(e.target.value); setPage(0); }}
          sx={{ minWidth: 220 }}
        >
          <MenuItem value="">{t('all_cashiers')}</MenuItem>
          {cashiers.map((c) => (
            <MenuItem key={c.id} value={c.id}>{c.firstName} {c.lastName}</MenuItem>
          ))}
        </TextField>
      </Paper>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>{t('cashier')}</TableCell>
              <TableCell>{t('opened')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('closed')}</TableCell>
              <TableCell align="right">{t('opening_amount')}</TableCell>
              <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{t('expected')}</TableCell>
              <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{t('actual')}</TableCell>
              <TableCell align="right">{t('variance')}</TableCell>
              <TableCell>{t('status')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={8}>{t('loading')}</TableCell></TableRow>
            ) : shifts.length === 0 ? (
              <TableRow><TableCell colSpan={8}>{t('no_shifts_found')}</TableCell></TableRow>
            ) : (
              shifts.map((s) => (
                <TableRow key={s.id}>
                  <TableCell>{s.cashierName || s.cashierId}</TableCell>
                  <TableCell>{new Date(s.openingTime).toLocaleString()}</TableCell>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{s.closingTime ? new Date(s.closingTime).toLocaleString() : '-'}</TableCell>
                  <TableCell align="right">{formatCurrency(s.openingAmount)}</TableCell>
                  <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{s.expectedAmount != null ? formatCurrency(s.expectedAmount) : '-'}</TableCell>
                  <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{s.closingAmount != null ? formatCurrency(s.closingAmount) : '-'}</TableCell>
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
                    <Chip size="small" label={s.status === 'OPEN' ? t('status_open') : t('status_closed')} color={s.status === 'OPEN' ? 'info' : 'default'} />
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