import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box, Typography, Paper, Tabs, Tab, TextField, MenuItem, Button, Grid,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  Chip, CircularProgress, Alert, Dialog, DialogTitle, DialogContent,
  DialogActions, Autocomplete, InputAdornment, TablePagination,
  TableSortLabel, Tooltip, ToggleButtonGroup, ToggleButton, Stack,
} from '@mui/material';
import {
  Search as SearchIcon,
  Download as DownloadIcon,
  Inventory2 as StockIcon,
  WarningAmber as WarningIcon,
  ErrorOutline as OutOfStockIcon,
  Inventory as InventoryTotalIcon,
  AttachMoney as MoneyIcon,
  Sell as SellIcon,
  TrendingUp as ProfitIcon,
  SwapVert as SwapVertIcon,
  EditOutlined as AdjustIcon,
  Visibility as ViewIcon,
} from '@mui/icons-material';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as ChartTooltip,
  ResponsiveContainer, Legend, PieChart, Pie, Cell,
} from 'recharts';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { inventoryService, productService, categoryService } from '../api/services';
import { formatCurrency, formatDateTime, downloadCsv } from '../utils/helpers';

// ── Shared bits ──────────────────────────────────────────────────────────────

const CHART_COLORS = ['#1976d2', '#388e3c', '#f57c00', '#7b1fa2', '#0288d1', '#c2185b', '#5d4037', '#455a64'];

const StatusChip = ({ stock, minStock, t }) => {
  if (stock <= 0) return <Chip size="small" icon={<OutOfStockIcon />} label={t('out_of_stock')} color="error" variant="filled" />;
  if (stock <= minStock) return <Chip size="small" icon={<WarningIcon />} label={t('low_stock')} color="warning" variant="outlined" />;
  return <Chip size="small" label={t('in_stock')} color="success" variant="filled" />;
};

const TYPE_META = {
  IN:            { key: 'type_in',             color: 'success' },
  OUT:           { key: 'type_out',            color: 'error' },
  ADJUSTMENT_IN: { key: 'type_adjustment_in',  color: 'success', variant: 'outlined' },
  ADJUSTMENT_OUT:{ key: 'type_adjustment_out', color: 'error',   variant: 'outlined' },
  ADJUSTMENT:    { key: 'type_adjustment',     color: 'warning' },
};

const REFERENCE_KEYS = {
  PURCHASE: 'ref_purchase',
  SALE: 'ref_sale',
  STOCK_ADJUSTMENT: 'ref_stock_adjustment',
  RETURN: 'ref_return',
};

const StatCard = ({ label, value, sub, icon, color = 'primary.main', to }) => {
  const navigate = useNavigate();
  return (
    <Paper
      elevation={0}
      onClick={() => to && navigate(to)}
      sx={{
        p: 2.5, borderRadius: 3, border: '1px solid', borderColor: 'divider',
        display: 'flex', alignItems: 'center', gap: 2,
        cursor: to ? 'pointer' : 'default',
        transition: 'box-shadow .15s',
        '&:hover': to ? { boxShadow: 2 } : {},
      }}
    >
      <Box sx={{ bgcolor: `${color}`, color: '#fff', borderRadius: 2, p: 1.25, display: 'flex' }}>
        {icon}
      </Box>
      <Box>
        <Typography variant="caption" color="text.secondary" fontWeight={600}>{label}</Typography>
        <Typography variant="h6" fontWeight={700}>{value}</Typography>
        {sub && <Typography variant="caption" color="text.disabled">{sub}</Typography>}
      </Box>
    </Paper>
  );
};

const MoneyCard = ({ label, value, icon, color }) => (
  <Paper elevation={0} sx={{ p: 2.5, borderRadius: 3, border: '1px solid', borderColor: 'divider', display: 'flex', alignItems: 'center', gap: 2 }}>
    <Box sx={{ bgcolor: color, color: '#fff', borderRadius: 2, p: 1.25, display: 'flex' }}>{icon}</Box>
    <Box>
      <Typography variant="caption" color="text.secondary" fontWeight={600}>{label}</Typography>
      <Typography variant="h6" fontWeight={700} sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>{formatCurrency(value)}</Typography>
    </Box>
  </Paper>
);

// ── Overview tab ─────────────────────────────────────────────────────────────

const OverviewTab = ({ onGoAdjust }) => {
  const { t } = useTranslation('inventory');
  const navigate = useNavigate();
  const [trendDays, setTrendDays] = useState(30);

  const summaryQ = useQuery({
    queryKey: ['inventory-summary'],
    queryFn: () => inventoryService.getSummary(),
  });
  const statsQ = useQuery({
    queryKey: ['movement-stats', trendDays],
    queryFn: () => inventoryService.getMovementStats(trendDays),
  });

  const s = summaryQ.data?.data;
  const stats = statsQ.data?.data;

  const trendData = useMemo(
    () => (stats?.daily || []).map((d) => ({
      date: d.date?.slice(5),
      [t('chart_in')]: d.inQty,
      [t('chart_out')]: -d.outQty,
    })),
    [stats, t]
  );

  const pieData = useMemo(() => {
    const cats = (s?.categoryBreakdown || []).slice(0, 8).map((c) => ({
      name: c.categoryName, value: Number(c.retailValue || 0),
    }));
    const rest = (s?.categoryBreakdown || []).slice(8)
      .reduce((sum, c) => sum + Number(c.retailValue || 0), 0);
    if (rest > 0) cats.push({ name: t('other'), value: rest });
    return cats.filter((c) => c.value > 0);
  }, [s, t]);

  if (summaryQ.isLoading) {
    return <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box>;
  }
  if (summaryQ.isError) {
    return <Alert severity="error">{t('failed_to_load_summary')}</Alert>;
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
      {/* Count cards */}
      <Grid container spacing={2}>
        <Grid item xs={6} md={3}>
          <StatCard
            label={t('active_products')}
            value={s.activeProducts ?? 0}
            sub={`${s.inactiveProducts ?? 0} ${t('inactive')}`}
            icon={<InventoryTotalIcon />}
            color="primary.main"
            to="/products"
          />
        </Grid>
        <Grid item xs={6} md={3}>
          <StatCard
            label={t('low_stock')}
            value={s.lowStockCount ?? 0}
            icon={<WarningIcon />}
            color="warning.main"
            to="/products"
          />
        </Grid>
        <Grid item xs={6} md={3}>
          <StatCard
            label={t('out_of_stock')}
            value={s.outOfStockCount ?? 0}
            icon={<OutOfStockIcon />}
            color="error.main"
          />
        </Grid>
        <Grid item xs={6} md={3}>
          <StatCard
            label={t('total_units')}
            value={(s.totalUnits ?? 0).toLocaleString()}
            sub={`${s.inStockCount ?? 0} ${t('in_stock').toLowerCase()}`}
            icon={<StockIcon />}
            color="info.main"
          />
        </Grid>
      </Grid>

      {/* Value cards */}
      <Grid container spacing={2}>
        <Grid item xs={12} md={4}>
          <MoneyCard label={t('cost_value')} value={s.costValue} icon={<MoneyIcon />} color="text.secondary" />
        </Grid>
        <Grid item xs={12} md={4}>
          <MoneyCard label={t('retail_value')} value={s.retailValue} icon={<SellIcon />} color="primary.main" />
        </Grid>
        <Grid item xs={12} md={4}>
          <MoneyCard label={t('potential_profit')} value={s.potentialProfit} icon={<ProfitIcon />} color="success.main" />
        </Grid>
      </Grid>

      {/* Charts */}
      <Grid container spacing={2}>
        <Grid item xs={12} md={7}>
          <Paper elevation={0} sx={{ p: 2.5, borderRadius: 3, border: '1px solid', borderColor: 'divider', height: '100%' }}>
            <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
              <Typography fontWeight={700} sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <SwapVertIcon color="primary" /> {t('movement_trend')}
              </Typography>
              <ToggleButtonGroup
                size="small"
                exclusive
                value={trendDays}
                onChange={(e, v) => v && setTrendDays(v)}
              >
                {[7, 14, 30].map((d) => (
                  <ToggleButton key={d} value={d}>{t('last_n_days', { days: d })}</ToggleButton>
                ))}
              </ToggleButtonGroup>
            </Box>
            {stats ? (
              <>
                <ResponsiveContainer width="100%" height={240}>
                  <BarChart data={trendData} margin={{ top: 5, right: 10, left: -15, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#eee" />
                    <XAxis dataKey="date" tick={{ fontSize: 11 }} />
                    <YAxis tick={{ fontSize: 11 }} allowDecimals={false} />
                    <ChartTooltip
                      formatter={(value, name) => [Math.abs(Number(value)), name]}
                      labelFormatter={(label) => label}
                    />
                    <Legend />
                    <Bar dataKey={t('chart_in')} fill="#2e7d32" radius={[3, 3, 0, 0]} />
                    <Bar dataKey={t('chart_out')} fill="#c62828" radius={[3, 3, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
                <Stack direction="row" spacing={1} justifyContent="center" sx={{ mt: 1 }}>
                  <Chip size="small" color="success" label={`${t('total_in')}: +${(stats.totalIn || 0).toLocaleString()}`} />
                  <Chip size="small" color="error" label={`${t('total_out')}: -${(stats.totalOut || 0).toLocaleString()}`} />
                  <Chip
                    size="small"
                    variant="outlined"
                    color={(stats.netChange || 0) >= 0 ? 'info' : 'warning'}
                    label={`${t('net_change')}: ${(stats.netChange >= 0 ? '+' : '') + (stats.netChange || 0).toLocaleString()}`}
                  />
                </Stack>
              </>
            ) : (
              <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box>
            )}
          </Paper>
        </Grid>
        <Grid item xs={12} md={5}>
          <Paper elevation={0} sx={{ p: 2.5, borderRadius: 3, border: '1px solid', borderColor: 'divider', height: '100%' }}>
            <Typography fontWeight={700} gutterBottom>{t('category_breakdown')}</Typography>
            {pieData.length === 0 ? (
              <Alert severity="info" sx={{ mt: 2 }}>{t('no_category_data')}</Alert>
            ) : (
              <ResponsiveContainer width="100%" height={280}>
                <PieChart>
                  <Pie
                    data={pieData}
                    dataKey="value"
                    nameKey="name"
                    innerRadius={55}
                    outerRadius={90}
                    paddingAngle={2}
                  >
                    {pieData.map((entry, i) => (
                      <Cell key={entry.name} fill={CHART_COLORS[i % CHART_COLORS.length]} />
                    ))}
                  </Pie>
                  <ChartTooltip formatter={(value) => formatCurrency(value)} />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                </PieChart>
              </ResponsiveContainer>
            )}
          </Paper>
        </Grid>
      </Grid>

      {/* Low stock watchlist */}
      <Paper elevation={0} sx={{ p: 2.5, borderRadius: 3, border: '1px solid', borderColor: 'divider' }}>
        <Typography fontWeight={700} gutterBottom sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <WarningIcon color="warning" /> {t('low_stock_watchlist')}
          <Chip size="small" color="warning" label={s.lowStockItems?.length || 0} sx={{ ml: 0.5 }} />
        </Typography>
        {(s.lowStockItems?.length || 0) === 0 ? (
          <Alert severity="success">{t('no_low_stock')}</Alert>
        ) : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow sx={{ '& th': { bgcolor: 'grey.50', fontWeight: 700, color: 'text.secondary' } }}>
                  <TableCell>{t('product')}</TableCell>
                  <TableCell>{t('sku')}</TableCell>
                  <TableCell align="right">{t('stock_quantity')}</TableCell>
                  <TableCell align="right">{t('min_stock_level')}</TableCell>
                  <TableCell align="right">{t('shortage')}</TableCell>
                  <TableCell align="right">{t('actions')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {s.lowStockItems.map((item) => (
                  <TableRow key={item.productId} hover>
                    <TableCell
                      sx={{ cursor: 'pointer', fontWeight: 500 }}
                      onClick={() => navigate(`/products/${item.productId}`)}
                    >
                      {item.productName}
                    </TableCell>
                    <TableCell sx={{ fontFamily: '"IBM Plex Mono", monospace', fontSize: 12 }}>{item.sku}</TableCell>
                    <TableCell align="right">{item.stockQuantity}</TableCell>
                    <TableCell align="right">{item.minStockLevel}</TableCell>
                    <TableCell align="right">
                      <Chip size="small" color="warning" label={`-${item.shortage}`} />
                    </TableCell>
                    <TableCell align="right">
                      <Button size="small" variant="outlined" onClick={() => onGoAdjust(item)}>
                        {t('adjust_stock')}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>
    </Box>
  );
};

// ── Stock tab ────────────────────────────────────────────────────────────────

const StockTab = ({ onAdjust }) => {
  const { t } = useTranslation('inventory');
  const navigate = useNavigate();

  const [search, setSearch] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [orderBy, setOrderBy] = useState('name');
  const [order, setOrder] = useState('asc');
  const [page, setPage] = useState(0);
  const [rowsPerPage, setRowsPerPage] = useState(10);

  const productsQ = useQuery({
    queryKey: ['inventory-products'],
    queryFn: () => inventoryService.getProducts({ size: 1000 }),
  });

  const categoriesQ = useQuery({
    queryKey: ['categories', 'inventory-filter'],
    queryFn: () => categoryService.getAll(0, 100),
  });

  const allProducts = productsQ.data?.data?.content || [];

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    let rows = allProducts.map((p) => ({
      ...p,
      stockValue: Number(p.costPrice || 0) * (p.stockQuantity || 0),
    }));
    if (q) {
      rows = rows.filter((p) =>
        p.name?.toLowerCase().includes(q) || p.sku?.toLowerCase().includes(q));
    }
    if (categoryId) rows = rows.filter((p) => p.categoryId === categoryId);
    if (statusFilter === 'in') rows = rows.filter((p) => p.stockQuantity > p.minStockLevel);
    if (statusFilter === 'low') rows = rows.filter((p) => p.stockQuantity > 0 && p.stockQuantity <= p.minStockLevel);
    if (statusFilter === 'out') rows = rows.filter((p) => p.stockQuantity <= 0);

    const dir = order === 'asc' ? 1 : -1;
    rows.sort((a, b) => {
      const av = a[orderBy], bv = b[orderBy];
      if (typeof av === 'string' || typeof bv === 'string') {
        return String(av ?? '').localeCompare(String(bv ?? '')) * dir;
      }
      return ((av ?? 0) - (bv ?? 0)) * dir;
    });
    return rows;
  }, [allProducts, search, categoryId, statusFilter, orderBy, order]);

  const handleSort = (field) => {
    if (orderBy === field) {
      setOrder((cur) => (cur === 'asc' ? 'desc' : 'asc'));
    } else {
      setOrderBy(field);
      setOrder('asc');
    }
  };

  const handleExportCsv = () => {
    downloadCsv(
      `inventory-stock-${new Date().toISOString().slice(0, 10)}.csv`,
      filtered.map((p) => [
        p.sku, p.name, p.categoryName || '',
        p.stockQuantity ?? 0, p.minStockLevel ?? 0,
        p.costPrice ?? 0, p.unitPrice ?? 0, p.stockValue.toFixed(2),
      ]),
      ['SKU', t('product'), t('category'), t('stock_quantity'), t('min_stock_level'),
        t('cost_price'), t('unit_price'), t('stock_value')]
    );
  };

  const sortHeader = (field, label, align = 'left') => (
    <TableCell align={align}>
      <TableSortLabel active={orderBy === field} direction={orderBy === field ? order : 'asc'} onClick={() => handleSort(field)}>
        {label}
      </TableSortLabel>
    </TableCell>
  );

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap', alignItems: 'center' }}>
        <TextField
          size="small"
          placeholder={t('search_products')}
          value={search}
          onChange={(e) => { setSearch(e.target.value); setPage(0); }}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start"><SearchIcon fontSize="small" /></InputAdornment>
            ),
          }}
          sx={{ width: 240 }}
        />
        <TextField
          size="small"
          select
          label={t('category')}
          value={categoryId}
          onChange={(e) => { setCategoryId(e.target.value); setPage(0); }}
          sx={{ minWidth: 160 }}
        >
          <MenuItem value="">{t('all_categories')}</MenuItem>
          {(categoriesQ.data?.data?.content || []).map((c) => (
            <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>
          ))}
        </TextField>
        <TextField
          size="small"
          select
          label={t('status')}
          value={statusFilter}
          onChange={(e) => { setStatusFilter(e.target.value); setPage(0); }}
          sx={{ minWidth: 140 }}
        >
          <MenuItem value="all">{t('filter_all')}</MenuItem>
          <MenuItem value="in">{t('in_stock')}</MenuItem>
          <MenuItem value="low">{t('low_stock')}</MenuItem>
          <MenuItem value="out">{t('out_of_stock')}</MenuItem>
        </TextField>
        <Box sx={{ flexGrow: 1 }} />
        <Button variant="outlined" startIcon={<DownloadIcon />} onClick={handleExportCsv}>
          {t('export_csv')}
        </Button>
      </Box>

      {productsQ.isLoading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}><CircularProgress /></Box>
      ) : productsQ.isError ? (
        <Alert severity="error">{t('failed_to_load_products')}</Alert>
      ) : (
        <Paper elevation={0} sx={{ borderRadius: 3, border: '1px solid', borderColor: 'divider' }}>
          <TableContainer sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow sx={{ '& th': { bgcolor: 'grey.50', fontWeight: 700, color: 'text.secondary', whiteSpace: 'nowrap' } }}>
                  <TableCell>{t('sku')}</TableCell>
                  {sortHeader('name', t('product'))}
                  <TableCell>{t('category')}</TableCell>
                  {sortHeader('stockQuantity', t('stock'), 'right')}
                  <TableCell align="right">{t('min_stock_level')}</TableCell>
                  <TableCell>{t('status')}</TableCell>
                  {sortHeader('stockValue', t('stock_value'), 'right')}
                  <TableCell align="right">{t('actions')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {filtered
                  .slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage)
                  .map((p) => (
                    <TableRow key={p.id} hover sx={{ '& td': { borderBottom: '1px solid', borderColor: 'divider' } }}>
                      <TableCell sx={{ fontFamily: '"IBM Plex Mono", monospace', fontSize: 12 }}>{p.sku}</TableCell>
                      <TableCell
                        sx={{ cursor: 'pointer', fontWeight: 500, '&:hover': { color: 'primary.main' } }}
                        onClick={() => navigate(`/products/${p.id}`)}
                      >
                        {p.name}
                      </TableCell>
                      <TableCell>{p.categoryName || t('uncategorized')}</TableCell>
                      <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace', fontWeight: 600 }}>
                        {p.stockQuantity ?? 0}
                      </TableCell>
                      <TableCell align="right" sx={{ color: 'text.secondary' }}>{p.minStockLevel ?? 0}</TableCell>
                      <TableCell>
                        <StatusChip stock={p.stockQuantity ?? 0} minStock={p.minStockLevel ?? 0} t={t} />
                      </TableCell>
                      <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>
                        {formatCurrency(p.stockValue)}
                      </TableCell>
                      <TableCell align="right">
                        <Tooltip title={t('adjust_stock')}>
                          <Button size="small" onClick={() => onAdjust(p)}><AdjustIcon fontSize="small" /></Button>
                        </Tooltip>
                        <Tooltip title={t('view_details')}>
                          <Button size="small" onClick={() => navigate(`/products/${p.id}`)}><ViewIcon fontSize="small" /></Button>
                        </Tooltip>
                      </TableCell>
                    </TableRow>
                  ))}
                {filtered.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={8} align="center" sx={{ py: 5, color: 'text.secondary' }}>
                      {t('no_products_found')}
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination
            component="div"
            count={filtered.length}
            page={page}
            onPageChange={(e, newPage) => setPage(newPage)}
            rowsPerPage={rowsPerPage}
            onRowsPerPageChange={(e) => { setRowsPerPage(parseInt(e.target.value, 10)); setPage(0); }}
            rowsPerPageOptions={[10, 25, 50]}
            labelRowsPerPage={t('rows_per_page')}
          />
        </Paper>
      )}
    </Box>
  );
};

// ── Movements tab ────────────────────────────────────────────────────────────

const toIsoStart = (dateStr) => (dateStr ? `${dateStr}T00:00:00` : null);
// Exclusive end = next local midnight, formatted without zone conversion so it
// matches the backend's zone-less LocalDateTime parsing
const toIsoEndExclusive = (dateStr) => {
  if (!dateStr) return null;
  const d = new Date(`${dateStr}T00:00:00`);
  d.setDate(d.getDate() + 1);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T00:00:00`;
};

const MovementsTab = () => {
  const { t } = useTranslation('inventory');

  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [type, setType] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [page, setPage] = useState(0);

  useEffect(() => {
    const timer = setTimeout(() => { setSearch(searchInput); setPage(0); }, 350);
    return () => clearTimeout(timer);
  }, [searchInput]);

  const movementsQ = useQuery({
    queryKey: ['stock-movements', { page, type, search, dateFrom, dateTo }],
    queryFn: () => inventoryService.getMovements({
      page,
      size: 20,
      type,
      search,
      dateFrom: toIsoStart(dateFrom),
      dateTo: toIsoEndExclusive(dateTo),
    }),
    placeholderData: (prev) => prev,
  });

  const rows = movementsQ.data?.data?.content || [];
  const totalElements = movementsQ.data?.data?.totalElements ?? 0;

  const hasFilters = type || search || dateFrom || dateTo;

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap', alignItems: 'center' }}>
        <TextField
          size="small"
          placeholder={t('search_products')}
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start"><SearchIcon fontSize="small" /></InputAdornment>
            ),
          }}
          sx={{ width: 240 }}
        />
        <TextField
          size="small"
          select
          label={t('movement_type')}
          value={type}
          onChange={(e) => { setType(e.target.value); setPage(0); }}
          sx={{ minWidth: 170 }}
        >
          <MenuItem value="">{t('filter_all')}</MenuItem>
          <MenuItem value="IN">{t('type_in')}</MenuItem>
          <MenuItem value="OUT">{t('type_out')}</MenuItem>
          <MenuItem value="ADJUSTMENT_IN">{t('type_adjustment_in')}</MenuItem>
          <MenuItem value="ADJUSTMENT_OUT">{t('type_adjustment_out')}</MenuItem>
        </TextField>
        <TextField
          size="small"
          type="date"
          label={t('date_from')}
          value={dateFrom}
          onChange={(e) => { setDateFrom(e.target.value); setPage(0); }}
          InputLabelProps={{ shrink: true }}
          sx={{ width: 165 }}
        />
        <TextField
          size="small"
          type="date"
          label={t('date_to')}
          value={dateTo}
          onChange={(e) => { setDateTo(e.target.value); setPage(0); }}
          InputLabelProps={{ shrink: true }}
          sx={{ width: 165 }}
        />
        {hasFilters && (
          <Button
            onClick={() => {
              setType(''); setDateFrom(''); setDateTo('');
              setSearchInput(''); setSearch(''); setPage(0);
            }}
          >
            {t('clear_filters')}
          </Button>
        )}
      </Box>

      {movementsQ.isLoading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}><CircularProgress /></Box>
      ) : movementsQ.isError ? (
        <Alert severity="error">{t('failed_to_load_movements')}</Alert>
      ) : (
        <Paper elevation={0} sx={{ borderRadius: 3, border: '1px solid', borderColor: 'divider' }}>
          <TableContainer sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow sx={{ '& th': { bgcolor: 'grey.50', fontWeight: 700, color: 'text.secondary', whiteSpace: 'nowrap' } }}>
                  <TableCell>{t('date')}</TableCell>
                  <TableCell>{t('product')}</TableCell>
                  <TableCell>{t('movement_type')}</TableCell>
                  <TableCell align="right">{t('qty')}</TableCell>
                  <TableCell>{t('reference')}</TableCell>
                  <TableCell>{t('by_user')}</TableCell>
                  <TableCell>{t('description')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((m) => {
                  const meta = TYPE_META[m.movementType] || TYPE_META.ADJUSTMENT;
                  const qty = m.quantity ?? 0;
                  const isIn = ['IN', 'ADJUSTMENT_IN'].includes(m.movementType);
                  return (
                    <TableRow key={m.id} hover sx={{ '& td': { borderBottom: '1px solid', borderColor: 'divider' } }}>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(m.movementDate)}</TableCell>
                      <TableCell>
                        <Typography variant="body2" fontWeight={500}>{m.productName}</Typography>
                        <Typography variant="caption" color="text.disabled" sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>
                          {m.sku}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Chip size="small" color={meta.color} variant={meta.variant || 'filled'} label={t(meta.key)} />
                      </TableCell>
                      <TableCell
                        align="right"
                        sx={{
                          fontFamily: '"IBM Plex Mono", monospace',
                          fontWeight: 700,
                          color: isIn ? 'success.main' : 'error.main',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        {isIn ? '+' : '-'}{Math.abs(qty)}
                      </TableCell>
                      <TableCell>
                        {REFERENCE_KEYS[m.referenceType] ? (
                          <Tooltip title={`#${m.referenceId}`}>
                            <Chip
                              size="small"
                              variant="outlined"
                              color="default"
                              label={`${t(REFERENCE_KEYS[m.referenceType])} #${m.referenceId}`}
                              sx={{ fontSize: 11 }}
                            />
                          </Tooltip>
                        ) : `#${m.referenceId}`}
                      </TableCell>
                      <TableCell>{m.createdByName || '-'}</TableCell>
                      <TableCell>
                        <Tooltip title={m.description || ''}>
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{
                              display: 'block',
                              maxWidth: 260,
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                            }}
                          >
                            {m.description || '-'}
                          </Typography>
                        </Tooltip>
                      </TableCell>
                    </TableRow>
                  );
                })}
                {rows.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={7} align="center" sx={{ py: 5, color: 'text.secondary' }}>
                      {t('no_movements_found')}
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination
            component="div"
            count={totalElements}
            page={page}
            onPageChange={(e, newPage) => setPage(newPage)}
            rowsPerPage={20}
            rowsPerPageOptions={[20]}
          />
        </Paper>
      )}
    </Box>
  );
};

// ── Adjust dialog ────────────────────────────────────────────────────────────

const DialogProductSearch = ({ value, onSelect }) => {
  const [inputValue, setInputValue] = useState('');
  const [debounced, setDebounced] = useState('');
  const { t } = useTranslation('inventory');

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(inputValue), 300);
    return () => clearTimeout(timer);
  }, [inputValue]);

  const { data } = useQuery({
    queryKey: ['stock-adjust-product-search', debounced],
    queryFn: () => productService.search(debounced, 0, 10),
    enabled: debounced.length >= 2,
  });
  const options = data?.data?.content || [];

  return (
    <Autocomplete
      size="small"
      options={options}
      getOptionLabel={(p) => (p?.name ? t('product_with_stock', { name: p.name, stock: p.stockQuantity }) : '')}
      isOptionEqualToValue={(option, val) => option.id === val?.id}
      value={value}
      onChange={(e, selected) => onSelect(selected)}
      inputValue={inputValue}
      onInputChange={(e, newVal) => setInputValue(newVal)}
      noOptionsText={inputValue.length < 2 ? t('type_to_search') : t('no_products_found')}
      renderInput={(params) => <TextField {...params} label={t('product')} placeholder={t('search_by_name_or_sku')} />}
    />
  );
};

const ADJUSTMENT_KEYS = ['inventory-products', 'products', 'low-stock', 'inventoryReport',
  'inventory-summary', 'stock-movements', 'movement-stats'];

const AdjustDialog = ({ open, onClose, initialProduct }) => {
  const { t } = useTranslation('inventory');
  const queryClient = useQueryClient();

  const [selectedProduct, setSelectedProduct] = useState(null);
  const [adjustmentType, setAdjustmentType] = useState('ADD');
  const [quantityChange, setQuantityChange] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    if (open) {
      setSelectedProduct(initialProduct || null);
      setAdjustmentType('ADD');
      setQuantityChange('');
      setReason('');
      setError('');
    }
  }, [open, initialProduct]);

  const mutation = useMutation({
    mutationFn: () =>
      inventoryService.adjustStock(selectedProduct.id, {
        productId: selectedProduct.id,
        quantityChange: Number(quantityChange),
        reason: reason.trim(),
      }),
    onSuccess: () => {
      ADJUSTMENT_KEYS.forEach((key) =>
        queryClient.invalidateQueries({ queryKey: [key] }));
      onClose();
    },
    onError: (err) =>
      setError(err.response?.data?.message || t('failed_to_adjust_stock')),
  });

  const handleQty = (val) => {
    if (val === '' || val === '-') { setQuantityChange(val); return; }
    let num = parseInt(val, 10);
    if (!Number.isNaN(num)) {
      num = adjustmentType === 'REMOVE' ? -Math.abs(num) : Math.abs(num);
      setQuantityChange(num.toString());
    }
  };

  const handleSubmit = () => {
    if (!selectedProduct) { setError(t('product_required')); return; }
    const qty = parseInt(quantityChange, 10);
    if (!qty || qty === 0) { setError(t('quantity_must_be_positive')); return; }
    if (!reason.trim()) { setError(t('reason_required')); return; }
    setError('');
    mutation.mutate();
  };

  return (
    <Dialog open={open} onClose={() => !mutation.isPending && onClose()} fullWidth maxWidth="sm">
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <AdjustIcon color="warning" /> {t('adjust_stock')}
      </DialogTitle>
      <DialogContent dividers>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <DialogProductSearch
            value={selectedProduct}
            onSelect={(p) => { setSelectedProduct(p); setError(''); }}
          />
          <Grid container spacing={2}>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth select
                label={t('adjustment_type')}
                value={adjustmentType}
                onChange={(e) => {
                  const val = e.target.value;
                  setAdjustmentType(val);
                  if (quantityChange) {
                    const num = parseInt(quantityChange, 10);
                    if (!Number.isNaN(num)) {
                      setQuantityChange((val === 'REMOVE' ? -Math.abs(num) : Math.abs(num)).toString());
                    }
                  }
                }}
                required
              >
                <MenuItem value="ADD">{t('add_stock')}</MenuItem>
                <MenuItem value="REMOVE">{t('remove_stock')}</MenuItem>
              </TextField>
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth type="number"
                label={t('quantity')}
                value={quantityChange}
                onChange={(e) => handleQty(e.target.value)}
                required
              />
            </Grid>
          </Grid>
          <TextField
            fullWidth required multiline rows={3}
            label={t('reason')}
            placeholder={t('adjust_reason_placeholder')}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={mutation.isPending}>{t('cancel')}</Button>
        <Button
          onClick={handleSubmit}
          color="warning"
          variant="contained"
          startIcon={mutation.isPending ? <CircularProgress size={18} sx={{ color: 'white' }} /> : null}
          disabled={mutation.isPending}
        >
          {mutation.isPending ? t('processing') : t('adjust_stock')}
        </Button>
      </DialogActions>
    </Dialog>
  );
};




// -- Page ---------------------------------------------------------------------

const Inventory = () => {
  const { t } = useTranslation('inventory');
  const [tab, setTab] = useState(0);
  const [adjustOpen, setAdjustOpen] = useState(false);
  const [adjustProduct, setAdjustProduct] = useState(null);

  const openAdjust = (product) => {
    setAdjustProduct(product || null);
    setAdjustOpen(true);
  };

  return (
    <Box>
      
      <Paper elevation={0} sx={{ borderRadius: 3, border: '1px solid', borderColor: 'divider', mb: 2.5 }}>
        <Tabs value={tab} onChange={(e, v) => setTab(v)} sx={{ px: 1 }}>
          <Tab icon={<ProfitIcon fontSize="small" />} iconPosition="start" label={t('overview')} />
          <Tab icon={<StockIcon fontSize="small" />} iconPosition="start" label={t('stock_levels')} />
          <Tab icon={<SwapVertIcon fontSize="small" />} iconPosition="start" label={t('movements')} />
        </Tabs>
      </Paper>

      {tab === 0 && <OverviewTab onGoAdjust={openAdjust} />}
      {tab === 1 && <StockTab onAdjust={openAdjust} />}
      {tab === 2 && <MovementsTab />}

      <AdjustDialog
        open={adjustOpen}
        onClose={() => setAdjustOpen(false)}
        initialProduct={adjustProduct}
      />
    </Box>
  );
};

export default Inventory;
