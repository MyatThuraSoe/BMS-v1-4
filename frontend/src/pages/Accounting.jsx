import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { reportService, expenseService } from '../api/services';
import { useAuth } from '../context/AuthContext';
import DashboardLayout from '../components/DashboardLayout';
import {
  Card,
  CardContent,
  Typography,
  Grid,
  Box,
  Button,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  MenuItem,
  FormControl,
  InputLabel,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  IconButton,
  Chip,
} from '@mui/material';
import {
  BarChart,
  Bar,
  PieChart,
  Pie,
  Cell,
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';
import EditIcon from '@mui/icons-material/Edit';
import DeleteIcon from '@mui/icons-material/Delete';
import AddIcon from '@mui/icons-material/Add';
import ReceiptIcon from '@mui/icons-material/Receipt';

const COLORS = ['#0088FE', '#00C49F', '#FFBB28', '#FF8042', '#8884D8', '#82CA9D', '#FFC658', '#FF6B6B', '#4ECDC4'];

const Accounting = () => {
  const navigate = useNavigate();
  const { user } = useAuth();
  const [currentDate] = useState(new Date());
  const [selectedYear, setSelectedYear] = useState(currentDate.getFullYear());
  const [selectedMonth, setSelectedMonth] = useState(currentDate.getMonth() + 1);
  const [summary, setSummary] = useState(null);
  const [expenses, setExpenses] = useState([]);
  const [loading, setLoading] = useState(true);
  const [expenseDialogOpen, setExpenseDialogOpen] = useState(false);
  const [editingExpense, setEditingExpense] = useState(null);
  const [expenseForm, setExpenseForm] = useState({
    category: '',
    description: '',
    amount: '',
    expenseDate: new Date().toISOString().split('T')[0],
  });
  const [receiptImage, setReceiptImage] = useState(null);

  const monthNames = [
    'January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December'
  ];

  useEffect(() => {
    fetchAccountingData();
  }, [selectedYear, selectedMonth]);

  const fetchAccountingData = async () => {
    setLoading(true);
    try {
      const summaryResponse = await reportService.getAccountingSummary(selectedYear, selectedMonth);
      setSummary(summaryResponse.data);

      const expensesResponse = await expenseService.getAll(0, 100);
      const allExpenses = expensesResponse.data.content || [];
      const filteredExpenses = allExpenses.filter(exp => {
        const expDate = new Date(exp.expenseDate);
        return expDate.getFullYear() === selectedYear && (expDate.getMonth() + 1) === selectedMonth;
      });
      setExpenses(filteredExpenses);
    } catch (error) {
      console.error('Error fetching accounting data:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleOpenAddExpense = () => {
    setEditingExpense(null);
    setExpenseForm({
      category: '',
      description: '',
      amount: '',
      expenseDate: new Date().toISOString().split('T')[0],
    });
    setReceiptImage(null);
    setExpenseDialogOpen(true);
  };

  const handleOpenEditExpense = (expense) => {
    setEditingExpense(expense);
    setExpenseForm({
      category: expense.category,
      description: expense.description || '',
      amount: expense.amount.toString(),
      expenseDate: expense.expenseDate,
    });
    setReceiptImage(null);
    setExpenseDialogOpen(true);
  };

  const handleCloseDialog = () => {
    setExpenseDialogOpen(false);
    setEditingExpense(null);
  };

  const handleSaveExpense = async () => {
    try {
      const expenseData = {
        ...expenseForm,
        amount: parseFloat(expenseForm.amount),
      };

      if (editingExpense) {
        await expenseService.update(editingExpense.id, expenseData);
      } else {
        await expenseService.create(expenseData);
      }

      handleCloseDialog();
      fetchAccountingData();
    } catch (error) {
      console.error('Error saving expense:', error);
      alert('Failed to save expense. Please try again.');
    }
  };

  const handleDeleteExpense = async (id) => {
    if (!window.confirm('Are you sure you want to delete this expense?')) return;

    try {
      await expenseService.delete(id);
      fetchAccountingData();
    } catch (error) {
      console.error('Error deleting expense:', error);
      alert('Failed to delete expense. Please try again.');
    }
  };

  const handleYearChange = (event) => {
    setSelectedYear(parseInt(event.target.value));
  };

  const handleMonthChange = (event) => {
    setSelectedMonth(parseInt(event.target.value));
  };

  const formatCurrency = (amount) => {
    if (amount === null || amount === undefined) return '$0.00';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
    }).format(amount);
  };

  const prepareExpenseChartData = () => {
    if (!summary || !summary.expensesByCategory || summary.expensesByCategory.length === 0) {
      return [{ name: 'No Expenses', value: 1 }];
    }
    return summary.expensesByCategory.map(cat => ({
      name: cat.category,
      value: parseFloat(cat.amount) || 0,
    }));
  };

  if (loading) {
    return (
      <DashboardLayout>
        <Box sx={{ p: 3, textAlign: 'center' }}>Loading...</Box>
      </DashboardLayout>
    );
  }

  return (
    <DashboardLayout>
      <Box sx={{ p: 3 }}>
        {/* Header with Month/Year Picker */}
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
          <Typography variant="h4" gutterBottom>
            Business Accounting
          </Typography>
          <Box sx={{ display: 'flex', gap: 2 }}>
            <FormControl size="small">
              <InputLabel>Month</InputLabel>
              <TextField
                select
                label="Month"
                value={selectedMonth}
                onChange={handleMonthChange}
                sx={{ minWidth: 120 }}
              >
                {monthNames.map((name, index) => (
                  <MenuItem key={index} value={index + 1}>{name}</MenuItem>
                ))}
              </TextField>
            </FormControl>
            <FormControl size="small">
              <InputLabel>Year</InputLabel>
              <TextField
                select
                label="Year"
                value={selectedYear}
                onChange={handleYearChange}
                sx={{ minWidth: 100 }}
              >
                {[2023, 2024, 2025, 2026].map(year => (
                  <MenuItem key={year} value={year}>{year}</MenuItem>
                ))}
              </TextField>
            </FormControl>
          </Box>
        </Box>

        {/* Summary Cards */}
        <Grid container spacing={3} sx={{ mb: 4 }}>
          <Grid item xs={12} sm={6} md={4}>
            <Card>
              <CardContent>
                <Typography color="textSecondary" gutterBottom>Total Income</Typography>
                <Typography variant="h5" color="primary">
                  {summary ? formatCurrency(summary.totalIncome) : '$0.00'}
                </Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid item xs={12} sm={6} md={4}>
            <Card>
              <CardContent>
                <Typography color="textSecondary" gutterBottom>COGS</Typography>
                <Typography variant="h5" color="textSecondary">
                  {summary ? formatCurrency(summary.totalCogs) : '$0.00'}
                </Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid item xs={12} sm={6} md={4}>
            <Card>
              <CardContent>
                <Typography color="textSecondary" gutterBottom>Gross Profit</Typography>
                <Typography variant="h5" color="success.main">
                  {summary ? formatCurrency(summary.grossProfit) : '$0.00'}
                </Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid item xs={12} sm={6} md={4}>
            <Card>
              <CardContent>
                <Typography color="textSecondary" gutterBottom>Total Expenses</Typography>
                <Typography variant="h5" color="error.main">
                  {summary ? formatCurrency(summary.totalExpenses) : '$0.00'}
                </Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid item xs={12} sm={6} md={4}>
            <Card>
              <CardContent>
                <Typography color="textSecondary" gutterBottom>Total Refunds</Typography>
                <Typography variant="h5" color="warning.main">
                  {summary ? formatCurrency(summary.totalRefunds) : '$0.00'}
                </Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid item xs={12} sm={6} md={4}>
            <Card sx={{ bgcolor: 'primary.light' }}>
              <CardContent>
                <Typography color="textSecondary" gutterBottom fontWeight="bold">Net Profit</Typography>
                <Typography variant="h4" color="primary.contrastText" fontWeight="bold">
                  {summary ? formatCurrency(summary.netProfit) : '$0.00'}
                </Typography>
              </CardContent>
            </Card>
          </Grid>
        </Grid>

        {/* Expense Breakdown Chart */}
        <Grid container spacing={3} sx={{ mb: 4 }}>
          <Grid item xs={12} md={6}>
            <Card>
              <CardContent>
                <Typography variant="h6" gutterBottom>Expenses by Category</Typography>
                <ResponsiveContainer width="100%" height={300}>
                  <PieChart>
                    <Pie
                      data={prepareExpenseChartData()}
                      cx="50%"
                      cy="50%"
                      labelLine={false}
                      label={({ name, percent }) => `${name}: ${(percent * 100).toFixed(0)}%`}
                      outerRadius={80}
                      fill="#8884d8"
                      dataKey="value"
                    >
                      {prepareExpenseChartData().map((entry, index) => (
                        <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                      ))}
                    </Pie>
                    <Tooltip formatter={(value) => formatCurrency(value)} />
                  </PieChart>
                </ResponsiveContainer>
              </CardContent>
            </Card>
          </Grid>
          <Grid item xs={12} md={6}>
            <Card>
              <CardContent>
                <Typography variant="h6" gutterBottom>Expense Breakdown</Typography>
                <TableContainer component={Paper} sx={{ maxHeight: 300, overflow: 'auto' }}>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>Category</TableCell>
                        <TableCell align="right">Amount</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {summary && summary.expensesByCategory && summary.expensesByCategory.length > 0 ? (
                        summary.expensesByCategory.map((cat, index) => (
                          <TableRow key={index}>
                            <TableCell>{cat.category}</TableCell>
                            <TableCell align="right">{formatCurrency(cat.amount)}</TableCell>
                          </TableRow>
                        ))
                      ) : (
                        <TableRow>
                          <TableCell colSpan={2} align="center">No expenses recorded</TableCell>
                        </TableRow>
                      )}
                    </TableBody>
                  </Table>
                </TableContainer>
              </CardContent>
            </Card>
          </Grid>
        </Grid>

        {/* Expense Log Table */}
        <Card>
          <CardContent>
            <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
              <Typography variant="h6">Expense Log - {monthNames[selectedMonth - 1]} {selectedYear}</Typography>
              {(user?.role === 'ADMIN' || user?.role === 'MANAGER') && (
                <Button
                  variant="contained"
                  startIcon={<AddIcon />}
                  onClick={handleOpenAddExpense}
                >
                  Add Expense
                </Button>
              )}
            </Box>
            <TableContainer component={Paper}>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>Date</TableCell>
                    <TableCell>Category</TableCell>
                    <TableCell>Description</TableCell>
                    <TableCell>Receipt</TableCell>
                    <TableCell align="right">Amount</TableCell>
                    <TableCell align="right">Actions</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {expenses.length > 0 ? (
                    expenses.map((expense) => (
                      <TableRow key={expense.id}>
                        <TableCell>{expense.expenseDate}</TableCell>
                        <TableCell>
                          <Chip label={expense.category} size="small" />
                        </TableCell>
                        <TableCell>{expense.description || '-'}</TableCell>
                        <TableCell>
                          {expense.hasReceiptImage ? (
                            <Chip icon={<ReceiptIcon />} label="Yes" size="small" color="primary" variant="outlined" />
                          ) : (
                            <Typography variant="body2" color="textSecondary">No</Typography>
                          )}
                        </TableCell>
                        <TableCell align="right">{formatCurrency(expense.amount)}</TableCell>
                        <TableCell align="right">
                          {(user?.role === 'ADMIN' || user?.role === 'MANAGER') && (
                            <>
                              <IconButton
                                size="small"
                                onClick={() => handleOpenEditExpense(expense)}
                              >
                                <EditIcon fontSize="small" />
                              </IconButton>
                              {user?.role === 'ADMIN' && (
                                <IconButton
                                  size="small"
                                  onClick={() => handleDeleteExpense(expense.id)}
                                  color="error"
                                >
                                  <DeleteIcon fontSize="small" />
                                </IconButton>
                              )}
                            </>
                          )}
                        </TableCell>
                      </TableRow>
                    ))
                  ) : (
                    <TableRow>
                      <TableCell colSpan={7} align="center">
                        No expenses recorded for this month
                      </TableCell>
                    </TableRow>
                  )}
                </TableBody>
              </Table>
            </TableContainer>
          </CardContent>
        </Card>

        {/* Add/Edit Expense Dialog */}
        <Dialog open={expenseDialogOpen} onClose={handleCloseDialog} maxWidth="sm" fullWidth>
          <DialogTitle>
            {editingExpense ? 'Edit Expense' : 'Add New Expense'}
          </DialogTitle>
          <DialogContent>
            <Box sx={{ pt: 2 }}>
              <FormControl fullWidth sx={{ mb: 2 }}>
                <InputLabel>Category</InputLabel>
                <TextField
                  select
                  label="Category"
                  value={expenseForm.category}
                  onChange={(e) => setExpenseForm({ ...expenseForm, category: e.target.value })}
                  required
                >
                  <MenuItem value="RENT">Rent</MenuItem>
                  <MenuItem value="UTILITIES">Utilities</MenuItem>
                  <MenuItem value="TRAVEL">Travel</MenuItem>
                  <MenuItem value="TAXES">Taxes</MenuItem>
                  <MenuItem value="SALARY">Salary</MenuItem>
                  <MenuItem value="SUPPLIES">Supplies</MenuItem>
                  <MenuItem value="MAINTENANCE">Maintenance</MenuItem>
                  <MenuItem value="MARKETING">Marketing</MenuItem>
                  <MenuItem value="OTHER">Other</MenuItem>
                </TextField>
              </FormControl>
              <TextField
                fullWidth
                label="Description"
                value={expenseForm.description}
                onChange={(e) => setExpenseForm({ ...expenseForm, description: e.target.value })}
                multiline
                rows={2}
                sx={{ mb: 2 }}
              />
              <TextField
                fullWidth
                label="Amount"
                type="number"
                value={expenseForm.amount}
                onChange={(e) => setExpenseForm({ ...expenseForm, amount: e.target.value })}
                required
                inputProps={{ step: "0.01", min: "0" }}
                sx={{ mb: 2 }}
              />
              <TextField
                fullWidth
                label="Expense Date"
                type="date"
                value={expenseForm.expenseDate}
                onChange={(e) => setExpenseForm({ ...expenseForm, expenseDate: e.target.value })}
                required
                InputLabelProps={{ shrink: true }}
              />
              {/* Receipt image upload could be added here in a future iteration */}
            </Box>
          </DialogContent>
          <DialogActions>
            <Button onClick={handleCloseDialog}>Cancel</Button>
            <Button onClick={handleSaveExpense} variant="contained" disabled={!expenseForm.category || !expenseForm.amount}>
              {editingExpense ? 'Update' : 'Add'}
            </Button>
          </DialogActions>
        </Dialog>
      </Box>
    </DashboardLayout>
  );
};

export default Accounting;
