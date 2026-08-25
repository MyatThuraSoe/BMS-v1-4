import { useEffect, useMemo, useState } from 'react';
import { Box, Typography, Paper, Grid, TextField, Button, Stack, CircularProgress, Table, TableBody, TableCell, TableHead, TableRow, TableContainer, Dialog, DialogTitle, DialogContent, DialogActions, MenuItem } from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { reportService, expenseService } from '../api/services';
import { formatCurrency } from '../utils/helpers';
import { notifySuccess, notifyError } from '../utils/notify';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer, Legend } from 'recharts';

const categories = ['RENT', 'UTILITIES', 'TRAVEL', 'TAXES', 'SALARY', 'SUPPLIES', 'MAINTENANCE', 'MARKETING', 'OTHER'];
const COLORS = ['#1976d2', '#2e7d32', '#ed6c02', '#9c27b0', '#d32f2f', '#00838f', '#6d4c41', '#5d4037', '#455a64'];

const Accounting = () => {
  const { t } = useTranslation('accounting');
  const today = new Date();
  const [month, setMonth] = useState(today.getMonth() + 1);
  const [year, setYear] = useState(today.getFullYear());
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingExpense, setEditingExpense] = useState(null);
  const [form, setForm] = useState({ category: 'OTHER', description: '', amount: '', expenseDate: today.toISOString().split('T')[0] });
  const [receiptFile, setReceiptFile] = useState(null);
  const [removeReceiptImage, setRemoveReceiptImage] = useState(false);

  const queryClient = useQueryClient();

  const { data: summaryData, isLoading: summaryLoading } = useQuery({
    queryKey: ['accountingSummary', year, month],
    queryFn: () => reportService.getAccountingSummary(year, month),
  });

  const { data: expensesData, isLoading: expensesLoading } = useQuery({
    queryKey: ['expenses', year, month],
    queryFn: () => expenseService.getAll(month, year),
  });

  const createMutation = useMutation({
    mutationFn: async ({ payload, file }) => {
      const response = await expenseService.create(payload);
      if (file && response?.data?.id) {
        await expenseService.uploadReceiptImage(response.data.id, file);
      }
      return response;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['expenses'] });
      queryClient.invalidateQueries({ queryKey: ['accountingSummary'] });
      setDialogOpen(false);
      resetForm();
      notifySuccess(t('expense_added'));
    },
    onError: (err) => notifyError(err.friendlyMessage || t('unable_add_expense')),
  });

  const updateMutation = useMutation({
    mutationFn: async ({ id, payload, file, removeImage }) => {
      const response = await expenseService.update(id, payload);
      if (removeImage) {
        await expenseService.deleteReceiptImage(id);
      }
      if (file) {
        await expenseService.uploadReceiptImage(id, file);
      }
      return response;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['expenses'] });
      queryClient.invalidateQueries({ queryKey: ['accountingSummary'] });
      setDialogOpen(false);
      resetForm();
      notifySuccess(t('expense_updated'));
    },
    onError: (err) => notifyError(err.friendlyMessage || t('unable_update_expense')),
  });

  const deleteMutation = useMutation({
    mutationFn: (id) => expenseService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['expenses'] });
      queryClient.invalidateQueries({ queryKey: ['accountingSummary'] });
      notifySuccess(t('expense_deleted'));
    },
    onError: (err) => notifyError(err.friendlyMessage || t('unable_delete_expense')),
  });

  const summary = summaryData?.data || {};
  const expenses = expensesData?.data || [];

  const summaryCards = useMemo(() => [
    { id: 'revenue', label: t('revenue'), value: summary.totalIncome || 0, changePercent: summary.incomeChangePercent, color: 'primary.main' },
    { id: 'refunds', label: t('refunds'), value: summary.totalRefunds || 0, color: 'error.main' },
    { id: 'cogs', label: t('cogs'), value: summary.totalCogs || 0, color: 'warning.main' },
    { id: 'gross_profit', label: t('gross_profit'), value: summary.grossProfit || 0, color: 'success.main' },
    { id: 'total_expenses', label: t('total_expenses'), value: summary.totalExpenses || 0, color: 'error.main' },
    { id: 'net_profit', label: t('net_profit'), value: summary.netProfit || 0, highlight: true, changePercent: summary.profitChangePercent, dynamic: true },
    { id: 'outstanding_ar', label: t('outstanding_ar'), value: summary.outstandingAr || 0, color: 'error.main' },
  ], [summary]);

  const categoryLabel = (cat) => t(`category_${String(cat).toLowerCase()}`, { defaultValue: cat });

  const openCreate = () => { setEditingExpense(null); resetForm(); setDialogOpen(true); };
  const openEdit = (expense) => {
    setEditingExpense(expense);
    setReceiptFile(null);
    setRemoveReceiptImage(false);
    setForm({ category: expense.category, description: expense.description || '', amount: expense.amount || '', expenseDate: expense.expenseDate || today.toISOString().split('T')[0] });
    setDialogOpen(true);
  };
  function resetForm() {
    setForm({ category: 'OTHER', description: '', amount: '', expenseDate: today.toISOString().split('T')[0] });
    setReceiptFile(null);
    setRemoveReceiptImage(false);
  }

  const handleSubmit = () => {
    const payload = { category: form.category, description: form.description, amount: Number(form.amount), expenseDate: form.expenseDate };
    if (editingExpense) {
      updateMutation.mutate({ id: editingExpense.id, payload, file: receiptFile, removeImage: removeReceiptImage });
    } else {
      createMutation.mutate({ payload, file: receiptFile });
    }
  };

  const expenseBreakdown = summary.expensesByCategory || [];

  return (
    <Box>
      <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="right" alignItems={{ xs: 'flex-start', md: 'center' }} spacing={2} sx={{ mb: 3 }}>
        <Button variant="contained" onClick={openCreate}>{t('add_expense')}</Button>
      </Stack>

      <Paper sx={{ p: 2, mb: 3 }}>
        <Grid container spacing={2}>
          <Grid item xs={12} md={4}>
            <TextField fullWidth label={t('month')} type="number" value={month} onChange={(e) => setMonth(Number(e.target.value))} inputProps={{ min: 1, max: 12 }} />
          </Grid>
          <Grid item xs={12} md={4}>
            <TextField fullWidth label={t('year')} type="number" value={year} onChange={(e) => setYear(Number(e.target.value))} />
          </Grid>
          <Grid item xs={12} md={4}>
            <Button fullWidth variant="outlined" sx={{ height: '56px' }} onClick={() => queryClient.invalidateQueries({ queryKey: ['accountingSummary'] })}>{t('refresh')}</Button>
          </Grid>
        </Grid>
      </Paper>

      <Grid container spacing={2} sx={{ mb: 3 }}>
        {summaryCards.map((card) => (
          <Grid item xs={12} sm={6} md={2} key={card.label}>
            <Paper sx={{ p: 2, border: card.highlight ? '2px solid #1976d2' : '1px solid #e0e0e0' }}>
              <Typography variant="body2" color="text.secondary">{card.label}</Typography>
              <Typography variant="h5" sx={{ fontWeight: 700, color: card.dynamic ? ((card.value || 0) >= 0 ? 'success.main' : 'error.main') : (card.color || 'text.primary') }}>
                {formatCurrency(card.value)}
              </Typography>
              {card.changePercent != null && (
                <Typography variant="caption" color={card.changePercent >= 0 ? 'success.main' : 'error.main'} sx={{ display: 'block', mt: 0.5 }}>
                  {card.changePercent >= 0 ? '\u25B2' : '\u25BC'} {t('change_vs_previous', { pct: Math.abs(Number(card.changePercent)).toFixed(1) })}
                </Typography>
              )}
              {card.changePercent == null && card.id === 'revenue' && summary.incomeChangePercent == null && (
                <Typography variant="caption" color="text.disabled" sx={{ display: 'block', mt: 0.5 }}>
                  {t('no_prior_period_data')}
                </Typography>
              )}
            </Paper>
          </Grid>
        ))}
      </Grid>

      {summaryLoading ? <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box> : null}

      <Grid container spacing={3}>
        <Grid item xs={12} md={5}>
          <Paper sx={{ p: 3, height: 360 }}>
            <Typography variant="h6" gutterBottom>{t('expense_breakdown')}</Typography>
            {expenseBreakdown.length === 0 ? (
              <Typography color="text.secondary">{t('no_expenses_month')}</Typography>
            ) : (
              <ResponsiveContainer width="100%" height="90%">
                <PieChart>
                  <Pie data={expenseBreakdown} dataKey="amount" nameKey="category" outerRadius={95}>
                    {expenseBreakdown.map((entry, index) => <Cell key={entry.category} fill={COLORS[index % COLORS.length]} />)}
                  </Pie>
                  <Tooltip formatter={(value) => formatCurrency(value)} />
                  <Legend />
                </PieChart>
              </ResponsiveContainer>
            )}
          </Paper>
        </Grid>

        <Grid item xs={12} md={7}>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" gutterBottom>{t('expense_log')}</Typography>
        {expensesLoading ? <CircularProgress /> : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t('receipt')}</TableCell>
                  <TableCell>{t('category')}</TableCell>
                  <TableCell>{t('description')}</TableCell>
                  <TableCell>{t('amount')}</TableCell>
                  <TableCell>{t('date')}</TableCell>
                  <TableCell align="right">{t('actions')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {expenses.length === 0 ? <TableRow><TableCell colSpan={6} align="center">{t('no_expenses_month')}</TableCell></TableRow> : expenses.map((expense) => (
                  <TableRow key={expense.id}>
                    <TableCell><ExpenseReceiptImage expenseId={expense.id} hasImage={expense.hasReceiptImage} /></TableCell>
                    <TableCell>{categoryLabel(expense.category)}</TableCell>
                    <TableCell>{expense.description}</TableCell>
                    <TableCell>{formatCurrency(expense.amount)}</TableCell>
                    <TableCell>{expense.expenseDate}</TableCell>
                    <TableCell align="right">
                      <Button size="small" onClick={() => openEdit(expense)}>{t('edit')}</Button>
                      <Button size="small" color="error" onClick={() => deleteMutation.mutate(expense.id)}>{t('delete')}</Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
          </Paper>
        </Grid>
      </Grid>

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{editingExpense ? t('edit_expense') : t('add_expense')}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField select label={t('category')} value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })}>
              {categories.map((category) => <MenuItem key={category} value={category}>{categoryLabel(category)}</MenuItem>)}
            </TextField>
            <TextField label={t('description')} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
            <TextField label={t('amount')} type="number" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
            <TextField label={t('expense_date')} type="date" value={form.expenseDate} onChange={(e) => setForm({ ...form, expenseDate: e.target.value })} InputLabelProps={{ shrink: true }} />
            {editingExpense?.hasReceiptImage && !removeReceiptImage && !receiptFile && (
              <Stack direction="row" spacing={2} alignItems="center">
                <ExpenseReceiptImage expenseId={editingExpense.id} hasImage size={72} />
                <Button color="error" onClick={() => setRemoveReceiptImage(true)}>{t('remove_receipt')}</Button>
              </Stack>
            )}
            <Button variant="outlined" component="label">
              {receiptFile ? receiptFile.name : t('upload_receipt_photo')}
              <input hidden type="file" accept="image/*" onChange={(e) => setReceiptFile(e.target.files?.[0] || null)} />
            </Button>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>{t('cancel')}</Button>
          <Button variant="contained" onClick={handleSubmit}>{t('save')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

const ExpenseReceiptImage = ({ expenseId, hasImage, size = 48 }) => {
  const { t } = useTranslation('accounting');
  const [imageUrl, setImageUrl] = useState(null);

  useEffect(() => {
    let objectUrl;
    let cancelled = false;

    if (hasImage && expenseId) {
      expenseService.getReceiptImage(expenseId).then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setImageUrl(objectUrl);
      }).catch(() => {
        if (!cancelled) setImageUrl(null);
      });
    } else {
      setImageUrl(null);
    }

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [expenseId, hasImage]);

  if (!hasImage || !imageUrl) {
    return (
      <Box sx={{ width: size, height: size, borderRadius: 1, bgcolor: 'action.hover' }} />
    );
  }

  return (
    <Box
      component="img"
      src={imageUrl}
      alt={t('receipt')}
      sx={{ width: size, height: size, objectFit: 'cover', borderRadius: 1 }}
    />
  );
};

export default Accounting;
