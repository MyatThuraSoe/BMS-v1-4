import { useEffect, useState } from 'react';
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  Divider, FormControlLabel, Grid, IconButton, MenuItem, Paper, Stack, Switch,
  Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs,
  TextField, Typography,
} from '@mui/material';
import {
  Add as AddIcon, Delete as DeleteIcon, LocalShipping as TransferIcon,
  Print as PrintIcon, QrCodeScanner as ScanIcon,
} from '@mui/icons-material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { locationService, productService } from '../api/services';
import { formatDate } from '../utils/helpers';
import { notifyError, notifySuccess } from '../utils/notify';

const TRANSFER_COLOR = {
  DRAFT: 'default',
  IN_TRANSIT: 'info',
  RECEIVED: 'success',
  COMPLETED: 'success',
  CANCELLED: 'error',
};

const LOCATION_TYPES = ['SHOP', 'BACKROOM', 'QUARANTINE', 'TRANSIT'];

const LocationDialog = ({ open, onClose, location }) => {
  const queryClient = useQueryClient();
  const [form, setForm] = useState({ code: '', name: '', type: 'BACKROOM', active: true });
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    setForm(location ? {
      code: location.code || '',
      name: location.name || '',
      type: location.type || 'BACKROOM',
      active: location.isActive !== false,
    } : { code: '', name: '', type: 'BACKROOM', active: true });
    setError('');
  }, [open, location]);

  const mutation = useMutation({
    mutationFn: (payload) => location
      ? locationService.update(location.id, payload)
      : locationService.create(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['locations'] });
      notifySuccess(location ? 'Location updated' : 'Location created');
      onClose();
    },
    onError: (err) => setError(err.friendlyMessage || err.response?.data?.message || 'Failed to save location'),
  });

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{location ? 'Edit location' : 'New location'}</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <Stack spacing={2} sx={{ pt: 1 }}>
          <TextField label="Code" value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} />
          <TextField label="Name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <TextField select label="Type" value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })}>
            {LOCATION_TYPES.map((type) => <MenuItem key={type} value={type}>{type}</MenuItem>)}
          </TextField>
          <FormControlLabel
            control={<Switch checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />}
            label="Active"
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={() => mutation.mutate(form)} disabled={mutation.isPending}>Save</Button>
      </DialogActions>
    </Dialog>
  );
};

const StockDialog = ({ location, open, onClose }) => {
  const { data, isLoading } = useQuery({
    queryKey: ['location-stock', location?.id],
    queryFn: () => locationService.getStock(location.id),
    enabled: open && Boolean(location?.id),
  });
  const rows = data?.data || [];
  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>Stock in {location?.code} — {location?.name}</DialogTitle>
      <DialogContent>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Product</TableCell>
                <TableCell>SKU</TableCell>
                <TableCell>Bin</TableCell>
                <TableCell align="right">Quantity</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {isLoading ? <TableRow><TableCell colSpan={4}>Loading...</TableCell></TableRow>
                : rows.length === 0 ? <TableRow><TableCell colSpan={4}>No stock in this location.</TableCell></TableRow>
                  : rows.map((row) => (
                    <TableRow key={`${row.locationId}-${row.productId}`}>
                      <TableCell>{row.name}</TableCell>
                      <TableCell>{row.sku}</TableCell>
                      <TableCell>{row.binLocation || '—'}</TableCell>
                      <TableCell align="right">{row.quantity}</TableCell>
                    </TableRow>
                  ))}
            </TableBody>
          </Table>
        </TableContainer>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  );
};

const TransferDialog = ({ open, onClose, locations, products }) => {
  const queryClient = useQueryClient();
  const [form, setForm] = useState({ fromLocationId: '', toLocationId: '', notes: '', items: [] });
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    const shop = locations.find((l) => l.type === 'SHOP');
    const backroom = locations.find((l) => l.type === 'BACKROOM');
    setForm({
      fromLocationId: backroom?.id || shop?.id || '',
      toLocationId: shop?.id || backroom?.id || '',
      notes: '',
      items: [{ productId: '', quantity: 1 }],
    });
    setError('');
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  const mutation = useMutation({
    mutationFn: (payload) => locationService.createTransfer(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['transfers'] });
      queryClient.invalidateQueries({ queryKey: ['location-stock'] });
      notifySuccess('Transfer created as a draft');
      onClose();
    },
    onError: (err) => setError(err.friendlyMessage || err.response?.data?.message || 'Failed to create transfer'),
  });

  const submit = () => {
    setError('');
    if (!form.fromLocationId || !form.toLocationId) { setError('Choose source and destination'); return; }
    if (form.fromLocationId === form.toLocationId) { setError('Source and destination must differ'); return; }
    const items = form.items
      .filter((item) => item.productId && Number(item.quantity) > 0)
      .map((item) => ({ productId: Number(item.productId), quantity: Number(item.quantity) }));
    if (items.length === 0) { setError('Add at least one product line'); return; }
    mutation.mutate({
      fromLocationId: Number(form.fromLocationId),
      toLocationId: Number(form.toLocationId),
      notes: form.notes || null,
      items,
    });
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>New stock transfer</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <Grid container spacing={2} sx={{ pt: 1 }}>
          <Grid item xs={12} sm={6}>
            <TextField select fullWidth label="From" value={form.fromLocationId} onChange={(e) => setForm({ ...form, fromLocationId: e.target.value })}>
              {locations.map((location) => <MenuItem key={location.id} value={location.id}>{location.code} — {location.name}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid item xs={12} sm={6}>
            <TextField select fullWidth label="To" value={form.toLocationId} onChange={(e) => setForm({ ...form, toLocationId: e.target.value })}>
              {locations.map((location) => <MenuItem key={location.id} value={location.id}>{location.code} — {location.name}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid item xs={12}>
            <Stack direction="row" justifyContent="space-between" alignItems="center">
              <Typography variant="subtitle2">Transfer lines</Typography>
              <Button size="small" startIcon={<AddIcon />} onClick={() => setForm({ ...form, items: [...form.items, { productId: '', quantity: 1 }] })}>
                Add line
              </Button>
            </Stack>
          </Grid>
          {form.items.map((item, index) => (
            <Grid item xs={12} key={index}>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
                <TextField
                  select
                  size="small"
                  fullWidth
                  label="Product"
                  value={item.productId}
                  onChange={(e) => {
                    const items = [...form.items];
                    items[index] = { ...items[index], productId: e.target.value };
                    setForm({ ...form, items });
                  }}
                >
                  {products.map((product) => (
                    <MenuItem key={product.id} value={product.id}>{product.name} ({product.sku})</MenuItem>
                  ))}
                </TextField>
                <TextField
                  size="small"
                  type="number"
                  label="Quantity"
                  value={item.quantity}
                  onChange={(e) => {
                    const items = [...form.items];
                    items[index] = { ...items[index], quantity: e.target.value };
                    setForm({ ...form, items });
                  }}
                  sx={{ minWidth: 130 }}
                />
                <IconButton color="error" onClick={() => setForm({ ...form, items: form.items.filter((_, i) => i !== index) })}>
                  <DeleteIcon />
                </IconButton>
              </Stack>
            </Grid>
          ))}
          <Grid item xs={12}>
            <TextField fullWidth label="Notes" value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
          </Grid>
        </Grid>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={submit} disabled={mutation.isPending}>Create transfer</Button>
      </DialogActions>
    </Dialog>
  );
};

const TransferDetail = ({ transfer, open, onClose }) => {
  if (!transfer) return null;
  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{transfer.transferNumber}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {transfer.fromLocation?.code} → {transfer.toLocation?.code} · {transfer.status} · {formatDate(transfer.createdAt)}
        </Typography>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Product</TableCell>
                <TableCell>SKU</TableCell>
                <TableCell align="right">Quantity</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(transfer.items || []).map((item) => (
                <TableRow key={item.id}>
                  <TableCell>{item.product?.name}</TableCell>
                  <TableCell>{item.product?.sku}</TableCell>
                  <TableCell align="right">{item.quantity}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
        {transfer.notes && <Alert severity="info" sx={{ mt: 2 }}>{transfer.notes}</Alert>}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  );
};

const Locations = () => {
  const queryClient = useQueryClient();
  const [tab, setTab] = useState(0);
  const [locationDialog, setLocationDialog] = useState({ open: false, location: null });
  const [stockLocation, setStockLocation] = useState(null);
  const [transferOpen, setTransferOpen] = useState(false);
  const [transferDetail, setTransferDetail] = useState(null);
  const [scanCode, setScanCode] = useState('');
  const [scanResult, setScanResult] = useState(null);
  const [rescue, setRescue] = useState({ fromLocationId: '', toLocationId: '', quantity: 1, reason: '' });
  const [includeUnassigned, setIncludeUnassigned] = useState(false);
  const [error, setError] = useState('');

  const { data: locationData, isLoading: locationsLoading } = useQuery({ queryKey: ['locations'], queryFn: locationService.getAll });
  const { data: productData } = useQuery({
    queryKey: ['location-products'],
    queryFn: () => productService.getAll(0, 500, 'name'),
  });
  const { data: transferData, isLoading: transfersLoading } = useQuery({
    queryKey: ['transfers'],
    queryFn: () => locationService.getTransfers({ page: 0, size: 100 }),
    enabled: tab === 1,
  });
  const { data: putAwayData, refetch: refetchPutAway } = useQuery({
    queryKey: ['put-away'],
    queryFn: locationService.getPutAwayList,
    enabled: tab === 2,
  });
  const { data: labelData, isLoading: labelsLoading } = useQuery({
    queryKey: ['bin-labels', includeUnassigned],
    queryFn: () => locationService.getBinLabels(includeUnassigned),
    enabled: tab === 4,
  });

  const locations = locationData?.data || [];
  const products = productData?.data?.content || [];
  const transfers = transferData?.data?.content || [];
  const putAway = putAwayData?.data || [];
  const labels = labelData?.data || [];
  const shopLocation = locations.find((l) => l.type === 'SHOP');

  const invalidateStock = () => {
    queryClient.invalidateQueries({ queryKey: ['transfers'] });
    queryClient.invalidateQueries({ queryKey: ['location-stock'] });
    queryClient.invalidateQueries({ queryKey: ['put-away'] });
    queryClient.invalidateQueries({ queryKey: ['products'] });
    queryClient.invalidateQueries({ queryKey: ['inventory'] });
  };

  const transferAction = useMutation({
    mutationFn: ({ action, id }) => {
      if (action === 'start') return locationService.startTransfer(id);
      if (action === 'receive') return locationService.receiveTransfer(id);
      if (action === 'cancel') return locationService.cancelTransfer(id);
      return locationService.completeTransfer(id);
    },
    onSuccess: (res, variables) => {
      invalidateStock();
      notifySuccess(`Transfer ${variables.action === 'start' ? 'dispatched' : variables.action === 'receive' ? 'received' : variables.action}`);
    },
    onError: (err) => notifyError(err.friendlyMessage || err.response?.data?.message || 'Transfer action failed'),
  });

  const scanMutation = useMutation({
    mutationFn: (code) => locationService.scan(code),
    onSuccess: (res) => {
      const result = res?.data;
      setScanResult(result);
      const shop = locations.find((l) => l.type === 'SHOP');
      const from = result?.locations?.[0]?.locationId || '';
      setRescue({ fromLocationId: from, toLocationId: shop?.id || '', quantity: 1, reason: '' });
      setError('');
    },
    onError: (err) => { setScanResult(null); setError(err.friendlyMessage || err.response?.data?.message || 'No product found'); },
  });

  const rescueMutation = useMutation({
    mutationFn: () => locationService.rescue({
      productId: scanResult.productId,
      fromLocationId: Number(rescue.fromLocationId),
      toLocationId: Number(rescue.toLocationId),
      quantity: Number(rescue.quantity),
      reason: rescue.reason || null,
    }),
    onSuccess: (res) => {
      setScanResult(res?.data || null);
      invalidateStock();
      notifySuccess('Stock moved successfully');
    },
    onError: (err) => notifyError(err.friendlyMessage || err.response?.data?.message || 'Failed to move stock'),
  });

  const putAwayMutation = useMutation({
    mutationFn: (row) => locationService.rescue({
      productId: row.productId,
      fromLocationId: row.locationId,
      toLocationId: shopLocation?.id,
      quantity: row.quantity,
      reason: 'PUT_AWAY',
    }),
    onSuccess: () => {
      invalidateStock();
      refetchPutAway();
      notifySuccess('Stock moved to the shop floor');
    },
    onError: (err) => notifyError(err.friendlyMessage || err.response?.data?.message || 'Put-away failed'),
  });

  const printLabels = () => {
    const win = window.open('', '_blank');
    if (!win) return;
    const rows = labels.map((label) => `
      <div class="label">
        <div class="bin">${label.binLocation || 'UNASSIGNED'}</div>
        <div class="name">${label.name}</div>
        <div class="sku">${label.sku || ''} ${label.abcClass ? `· ABC ${label.abcClass}` : ''}</div>
        <div class="barcode">${label.barcode || ''}</div>
      </div>`).join('');
    win.document.write(`<html><head><title>Bin labels</title><style>
      body{font-family:Arial,sans-serif;margin:12px}
      .label{display:inline-block;width:220px;border:1px solid #333;border-radius:6px;padding:10px;margin:6px;vertical-align:top}
      .bin{font-size:22px;font-weight:bold}
      .name{font-size:13px;margin-top:4px}
      .sku{font-size:11px;color:#555;margin-top:2px}
      .barcode{font-family:monospace;font-size:12px;margin-top:8px;letter-spacing:1px}
    </style></head><body>${rows}</body></html>`);
    win.document.close();
    win.focus();
    win.print();
  };

  return (
    <Box>
      <Typography variant="h5" fontWeight={600}>Locations &amp; Transfers</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Multi-location stock, put-away, bin scanning, misplaced-stock rescue and transfers
      </Typography>

      <Tabs value={tab} onChange={(e, value) => setTab(value)} variant="scrollable" sx={{ mb: 2 }}>
        <Tab label="Locations" />
        <Tab icon={<TransferIcon />} iconPosition="start" label="Transfers" />
        <Tab label="Put-away" />
        <Tab icon={<ScanIcon />} iconPosition="start" label="Scan &amp; rescue" />
        <Tab label="Bin labels" />
      </Tabs>

      {tab === 0 && (
        <Paper>
          <Stack direction="row" justifyContent="flex-end" sx={{ p: 2 }}>
            <Button variant="contained" startIcon={<AddIcon />} onClick={() => setLocationDialog({ open: true, location: null })}>
              New location
            </Button>
          </Stack>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Code</TableCell>
                  <TableCell>Name</TableCell>
                  <TableCell>Type</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {locationsLoading ? <TableRow><TableCell colSpan={5}>Loading...</TableCell></TableRow>
                  : locations.length === 0 ? <TableRow><TableCell colSpan={5}>No locations configured.</TableCell></TableRow>
                    : locations.map((location) => (
                      <TableRow key={location.id} hover>
                        <TableCell>{location.code}</TableCell>
                        <TableCell>{location.name}</TableCell>
                        <TableCell><Chip size="small" label={location.type} /></TableCell>
                        <TableCell>{location.isActive ? 'Active' : 'Inactive'}</TableCell>
                        <TableCell align="right">
                          <Button size="small" onClick={() => setStockLocation(location)}>Stock</Button>
                          <Button size="small" onClick={() => setLocationDialog({ open: true, location })}>Edit</Button>
                        </TableCell>
                      </TableRow>
                    ))}
              </TableBody>
            </Table>
          </TableContainer>
        </Paper>
      )}

      {tab === 1 && (
        <Paper>
          <Stack direction="row" justifyContent="flex-end" sx={{ p: 2 }}>
            <Button variant="contained" startIcon={<AddIcon />} onClick={() => setTransferOpen(true)}>New transfer</Button>
          </Stack>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Number</TableCell>
                  <TableCell>From</TableCell>
                  <TableCell>To</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Created</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {transfersLoading ? <TableRow><TableCell colSpan={6}>Loading...</TableCell></TableRow>
                  : transfers.length === 0 ? <TableRow><TableCell colSpan={6}>No transfers yet.</TableCell></TableRow>
                    : transfers.map((transfer) => (
                      <TableRow key={transfer.id} hover>
                        <TableCell>{transfer.transferNumber}</TableCell>
                        <TableCell>{transfer.fromLocation?.code}</TableCell>
                        <TableCell>{transfer.toLocation?.code}</TableCell>
                        <TableCell>
                          <Chip size="small" label={transfer.status} color={TRANSFER_COLOR[transfer.status] || 'default'} />
                        </TableCell>
                        <TableCell>{formatDate(transfer.createdAt)}</TableCell>
                        <TableCell align="right">
                          <Button size="small" onClick={() => setTransferDetail(transfer)}>View</Button>
                          {transfer.status === 'DRAFT' && (
                            <Button size="small" onClick={() => transferAction.mutate({ action: 'start', id: transfer.id })}>Dispatch</Button>
                          )}
                          {transfer.status === 'IN_TRANSIT' && (
                            <Button size="small" color="success" onClick={() => transferAction.mutate({ action: 'receive', id: transfer.id })}>Receive</Button>
                          )}
                          {transfer.status === 'DRAFT' && (
                            <Button size="small" color="error" onClick={() => transferAction.mutate({ action: 'cancel', id: transfer.id })}>Cancel</Button>
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
              </TableBody>
            </Table>
          </TableContainer>
        </Paper>
      )}

      {tab === 2 && (
        <Paper sx={{ p: 2 }}>
          <Typography variant="subtitle1" fontWeight={600} sx={{ mb: 1 }}>Backroom stock waiting for put-away</Typography>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Product</TableCell>
                  <TableCell>SKU</TableCell>
                  <TableCell align="right">Quantity</TableCell>
                  <TableCell>Suggested bin</TableCell>
                  <TableCell align="right">Action</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {putAway.length === 0 ? <TableRow><TableCell colSpan={5}>Nothing is waiting in the backroom.</TableCell></TableRow>
                  : putAway.map((row) => (
                    <TableRow key={`${row.locationId}-${row.productId}`}>
                      <TableCell>{row.name}</TableCell>
                      <TableCell>{row.sku}</TableCell>
                      <TableCell align="right">{row.quantity}</TableCell>
                      <TableCell>{row.suggestedBin || 'Unassigned'}</TableCell>
                      <TableCell align="right">
                        <Button size="small" variant="contained" onClick={() => putAwayMutation.mutate(row)} disabled={!shopLocation || putAwayMutation.isPending}>
                          Move to shop
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
              </TableBody>
            </Table>
          </TableContainer>
        </Paper>
      )}

      {tab === 3 && (
        <Paper sx={{ p: 2 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'center' }} sx={{ mb: 2 }}>
            <TextField
              label="Scan barcode, SKU or bin"
              value={scanCode}
              onChange={(e) => setScanCode(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && scanCode.trim()) scanMutation.mutate(scanCode.trim()); }}
              fullWidth
            />
            <Button variant="contained" startIcon={<ScanIcon />} onClick={() => scanMutation.mutate(scanCode.trim())} disabled={!scanCode.trim() || scanMutation.isPending}>
              Scan
            </Button>
          </Stack>

          {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

          {scanResult && (
            <Grid container spacing={2}>
              <Grid item xs={12} md={6}>
                <Paper variant="outlined" sx={{ p: 2 }}>
                  <Typography variant="subtitle1" fontWeight={600}>{scanResult.name}</Typography>
                  <Typography variant="body2" color="text.secondary">
                    SKU {scanResult.sku} · Bin {scanResult.expectedBin || 'unassigned'} · ABC {scanResult.abcClass || '—'}
                  </Typography>
                  <Typography variant="body2" sx={{ mt: 1 }}>
                    Shop floor: <strong>{scanResult.shopQuantity}</strong>
                  </Typography>
                  <Divider sx={{ my: 1 }} />
                  <Typography variant="subtitle2" sx={{ mb: 1 }}>All locations</Typography>
                  {(scanResult.locations || []).map((row) => (
                    <Typography key={`${row.locationId}-${row.productId}`} variant="body2">
                      {row.locationCode} ({row.locationType}): {row.quantity}
                    </Typography>
                  ))}
                </Paper>
              </Grid>
              <Grid item xs={12} md={6}>
                <Paper variant="outlined" sx={{ p: 2 }}>
                  <Typography variant="subtitle2" sx={{ mb: 1 }}>Move / rescue stock</Typography>
                  <Stack spacing={2}>
                    <TextField select label="From" value={rescue.fromLocationId} onChange={(e) => setRescue({ ...rescue, fromLocationId: e.target.value })}>
                      {(scanResult.locations || []).map((row) => (
                        <MenuItem key={`${row.locationId}-${row.productId}`} value={row.locationId}>
                          {row.locationCode} ({row.quantity})
                        </MenuItem>
                      ))}
                    </TextField>
                    <TextField select label="To" value={rescue.toLocationId} onChange={(e) => setRescue({ ...rescue, toLocationId: e.target.value })}>
                      {locations.map((location) => (
                        <MenuItem key={location.id} value={location.id}>{location.code} — {location.name}</MenuItem>
                      ))}
                    </TextField>
                    <TextField type="number" label="Quantity" value={rescue.quantity} onChange={(e) => setRescue({ ...rescue, quantity: e.target.value })} />
                    <TextField label="Reason" value={rescue.reason} onChange={(e) => setRescue({ ...rescue, reason: e.target.value })} />
                    <Button
                      variant="contained"
                      onClick={() => rescueMutation.mutate()}
                      disabled={!rescue.fromLocationId || !rescue.toLocationId || rescueMutation.isPending}
                    >
                      Move stock
                    </Button>
                  </Stack>
                </Paper>
              </Grid>
            </Grid>
          )}
        </Paper>
      )}

      {tab === 4 && (
        <Paper sx={{ p: 2 }}>
          <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
            <FormControlLabel
              control={<Switch checked={includeUnassigned} onChange={(e) => setIncludeUnassigned(e.target.checked)} />}
              label="Include products without a bin"
            />
            <Button variant="contained" startIcon={<PrintIcon />} onClick={printLabels} disabled={labels.length === 0}>
              Print labels
            </Button>
          </Stack>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Bin</TableCell>
                  <TableCell>Product</TableCell>
                  <TableCell>SKU</TableCell>
                  <TableCell>Barcode</TableCell>
                  <TableCell>ABC</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {labelsLoading ? <TableRow><TableCell colSpan={5}>Loading...</TableCell></TableRow>
                  : labels.length === 0 ? <TableRow><TableCell colSpan={5}>No bin labels found.</TableCell></TableRow>
                    : labels.map((label) => (
                      <TableRow key={label.productId}>
                        <TableCell>{label.binLocation || '—'}</TableCell>
                        <TableCell>{label.name}</TableCell>
                        <TableCell>{label.sku}</TableCell>
                        <TableCell>{label.barcode || '—'}</TableCell>
                        <TableCell>{label.abcClass || '—'}</TableCell>
                      </TableRow>
                    ))}
              </TableBody>
            </Table>
          </TableContainer>
        </Paper>
      )}

      <LocationDialog
        open={locationDialog.open}
        location={locationDialog.location}
        onClose={() => setLocationDialog({ open: false, location: null })}
      />
      <StockDialog location={stockLocation} open={Boolean(stockLocation)} onClose={() => setStockLocation(null)} />
      <TransferDialog open={transferOpen} onClose={() => setTransferOpen(false)} locations={locations} products={products} />
      <TransferDetail transfer={transferDetail} open={Boolean(transferDetail)} onClose={() => setTransferDetail(null)} />
    </Box>
  );
};

export default Locations;
