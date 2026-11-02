import { useEffect, useMemo, useState } from 'react';
import {
  Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent,
  DialogTitle, FormControlLabel, Grid, MenuItem, Paper, Tab, Table,
  TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField,
  Typography,
} from '@mui/material';
import { Inventory2 as ReceiveIcon, Undo as ReturnIcon } from '@mui/icons-material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { locationService, purchaseService } from '../api/services';
import { formatCurrency, formatDate } from '../utils/helpers';
import { notifySuccess } from '../utils/notify';

const STATUS_COLOR = {
  ORDERED: 'warning',
  PARTIAL: 'info',
  RECEIVED: 'success',
};

const ReceiveDialog = ({ purchase, locations, open, onClose }) => {
  const queryClient = useQueryClient();
  const [rows, setRows] = useState({});
  const [manager, setManager] = useState({ username: '', password: '' });
  const [cancelRemainder, setCancelRemainder] = useState(false);
  const [notes, setNotes] = useState('');
  const [error, setError] = useState('');

  const destinationLocations = useMemo(
    () => (locations || []).filter((location) => location.type === 'SHOP' || location.type === 'BACKROOM'),
    [locations]
  );

  useEffect(() => {
    if (!open || !purchase) return;
    const next = {};
    (purchase.items || []).forEach((item) => {
      next[item.id] = {
        receivedQuantity: Math.max(0, (item.quantity || 0) - (item.receivedQuantity || 0)),
        quarantineQuantity: 0,
        locationId: destinationLocations.find((l) => l.type === 'SHOP')?.id || '',
      };
    });
    setRows(next);
    setManager({ username: '', password: '' });
    setCancelRemainder(false);
    setNotes('');
    setError('');
  }, [open, purchase?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const mutation = useMutation({
    mutationFn: (payload) => purchaseService.receive(purchase.id, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['open-purchases'] });
      queryClient.invalidateQueries({ queryKey: ['purchases'] });
      queryClient.invalidateQueries({ queryKey: ['products'] });
      queryClient.invalidateQueries({ queryKey: ['inventory'] });
      queryClient.invalidateQueries({ queryKey: ['put-away'] });
      notifySuccess('Purchase received');
      onClose();
    },
    onError: (err) => setError(err.friendlyMessage || err.response?.data?.message || 'Failed to receive purchase'),
  });

  const setRow = (id, patch) => setRows((prev) => ({ ...prev, [id]: { ...prev[id], ...patch } }));

  const submit = () => {
    setError('');
    const items = (purchase.items || [])
      .map((item) => ({
        purchaseItemId: item.id,
        receivedQuantity: Number(rows[item.id]?.receivedQuantity || 0),
        quarantineQuantity: Number(rows[item.id]?.quarantineQuantity || 0),
        locationId: rows[item.id]?.locationId || null,
      }))
      .filter((item) => item.receivedQuantity > 0);
    if (items.length === 0 && !cancelRemainder) {
      setError('Enter a received quantity, or choose to cancel the remainder');
      return;
    }
    mutation.mutate({
      items,
      managerUsername: manager.username || null,
      managerPassword: manager.password || null,
      cancelRemainder,
      notes: notes || null,
    });
  };

  if (!purchase) return null;
  const outstanding = (purchase.items || []).reduce(
    (sum, item) => sum + Math.max(0, (item.quantity || 0) - (item.receivedQuantity || 0)), 0
  );

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>
        Receive {purchase.purchaseNumber}
        <Typography variant="body2" color="text.secondary">
          {purchase.supplierName} · {formatDate(purchase.purchaseDate)} · {outstanding} unit(s) outstanding
        </Typography>
      </DialogTitle>
      <DialogContent>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <TableContainer sx={{ mb: 2 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Product</TableCell>
                <TableCell align="right">Ordered</TableCell>
                <TableCell align="right">Received</TableCell>
                <TableCell align="right" sx={{ width: 110 }}>Receive now</TableCell>
                <TableCell align="right" sx={{ width: 110 }}>Quarantine</TableCell>
                <TableCell sx={{ minWidth: 150 }}>Destination</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(purchase.items || []).map((item) => (
                <TableRow key={item.id}>
                  <TableCell>
                    {item.productName}
                    <Typography variant="caption" display="block" color="text.secondary">
                      Landed unit cost {formatCurrency(item.baseUnitCost || item.unitCost)}
                      {item.binLocation ? ` · Bin ${item.binLocation}` : ''}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">{item.quantity}</TableCell>
                  <TableCell align="right">{item.receivedQuantity}</TableCell>
                  <TableCell align="right">
                    <TextField
                      type="number"
                      size="small"
                      value={rows[item.id]?.receivedQuantity ?? ''}
                      onChange={(e) => setRow(item.id, { receivedQuantity: e.target.value })}
                      inputProps={{ min: 0, style: { textAlign: 'right' } }}
                    />
                  </TableCell>
                  <TableCell align="right">
                    <TextField
                      type="number"
                      size="small"
                      value={rows[item.id]?.quarantineQuantity ?? 0}
                      onChange={(e) => setRow(item.id, { quarantineQuantity: e.target.value })}
                      inputProps={{ min: 0, style: { textAlign: 'right' } }}
                    />
                  </TableCell>
                  <TableCell>
                    <TextField
                      select
                      size="small"
                      fullWidth
                      value={rows[item.id]?.locationId || ''}
                      onChange={(e) => setRow(item.id, { locationId: e.target.value })}
                    >
                      {destinationLocations.map((location) => (
                        <MenuItem key={location.id} value={location.id}>
                          {location.code} — {location.name}
                        </MenuItem>
                      ))}
                    </TextField>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>

        <FormControlLabel
          control={<Checkbox checked={cancelRemainder} onChange={(e) => setCancelRemainder(e.target.checked)} />}
          label="Short shipment: cancel the outstanding quantity and close this PO"
        />

        <Alert severity="info" sx={{ mt: 1 }}>
          Receiving more than the ordered quantity requires a manager or administrator approval.
        </Alert>
        <Grid container spacing={2} sx={{ mt: 0.5 }}>
          <Grid item xs={12} sm={6}>
            <TextField fullWidth size="small" label="Manager username (over-shipment)" value={manager.username} onChange={(e) => setManager({ ...manager, username: e.target.value })} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <TextField fullWidth size="small" type="password" label="Manager password" value={manager.password} onChange={(e) => setManager({ ...manager, password: e.target.value })} />
          </Grid>
          <Grid item xs={12}>
            <TextField fullWidth size="small" label="Receiving notes" value={notes} onChange={(e) => setNotes(e.target.value)} />
          </Grid>
        </Grid>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={submit} disabled={mutation.isPending}>Receive</Button>
      </DialogActions>
    </Dialog>
  );
};

const RtvDialog = ({ purchase, open, onClose }) => {
  const queryClient = useQueryClient();
  const [rows, setRows] = useState({});
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open || !purchase) return;
    const next = {};
    (purchase.items || []).forEach((item) => {
      next[item.id] = Math.max(0, (item.receivedQuantity || 0) - (item.returnedQuantity || 0));
    });
    setRows(next);
    setReason('');
    setError('');
  }, [open, purchase?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const mutation = useMutation({
    mutationFn: (payload) => purchaseService.returnToVendor(purchase.id, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['purchases'] });
      queryClient.invalidateQueries({ queryKey: ['products'] });
      queryClient.invalidateQueries({ queryKey: ['inventory'] });
      notifySuccess('Goods returned to vendor');
      onClose();
    },
    onError: (err) => setError(err.friendlyMessage || err.response?.data?.message || 'Failed to return goods'),
  });

  if (!purchase) return null;

  const submit = () => {
    const items = (purchase.items || [])
      .map((item) => ({ purchaseItemId: item.id, quantity: Number(rows[item.id] || 0), reasonCode: 'RETURN_TO_VENDOR' }))
      .filter((item) => item.quantity > 0);
    if (items.length === 0) { setError('Enter at least one return quantity'); return; }
    mutation.mutate({ items, reason: reason || null });
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>Return to vendor — {purchase.purchaseNumber}</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <TableContainer sx={{ mb: 2 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Product</TableCell>
                <TableCell align="right">Received</TableCell>
                <TableCell align="right">Already returned</TableCell>
                <TableCell align="right" sx={{ width: 120 }}>Return now</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(purchase.items || []).map((item) => {
                const available = Math.max(0, (item.receivedQuantity || 0) - (item.returnedQuantity || 0));
                return (
                  <TableRow key={item.id}>
                    <TableCell>{item.productName}</TableCell>
                    <TableCell align="right">{item.receivedQuantity}</TableCell>
                    <TableCell align="right">{item.returnedQuantity}</TableCell>
                    <TableCell align="right">
                      <TextField
                        type="number"
                        size="small"
                        value={rows[item.id] ?? 0}
                        onChange={(e) => setRows({ ...rows, [item.id]: e.target.value })}
                        inputProps={{ min: 0, max: available, style: { textAlign: 'right' } }}
                        disabled={available === 0}
                      />
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableContainer>
        <TextField fullWidth label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} />
        <Alert severity="info" sx={{ mt: 2 }}>
          Quarantined units are returned first, then sellable shop-floor stock.
        </Alert>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button color="warning" variant="contained" onClick={submit} disabled={mutation.isPending}>Return goods</Button>
      </DialogActions>
    </Dialog>
  );
};

const Receiving = () => {
  const [tab, setTab] = useState(0);
  const [receivePurchase, setReceivePurchase] = useState(null);
  const [rtvPurchase, setRtvPurchase] = useState(null);

  const { data: openData, isLoading: openLoading, error: openError } = useQuery({
    queryKey: ['open-purchases'],
    queryFn: purchaseService.getOpenPurchases,
  });

  const { data: purchaseData, isLoading: purchaseLoading } = useQuery({
    queryKey: ['purchases', 'rtv', 0, 100],
    queryFn: () => purchaseService.getAll(0, 100),
    enabled: tab === 1,
  });

  const { data: locationData } = useQuery({ queryKey: ['locations'], queryFn: locationService.getAll });

  const openPurchases = openData?.data || [];
  const purchases = (purchaseData?.data?.content || []).filter((p) => p.receivingStatus !== 'ORDERED');
  const locations = locationData?.data || [];

  return (
    <Box>
      <Typography variant="h5" fontWeight={600}>Receiving &amp; Returns</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Receive open purchase orders, quarantine damaged units and return goods to suppliers
      </Typography>

      <Tabs value={tab} onChange={(e, value) => setTab(value)} sx={{ mb: 2 }}>
        <Tab icon={<ReceiveIcon />} iconPosition="start" label="Receive purchase orders" />
        <Tab icon={<ReturnIcon />} iconPosition="start" label="Return to vendor" />
      </Tabs>

      {openError && <Alert severity="error" sx={{ mb: 2 }}>Failed to load open purchase orders</Alert>}

      {tab === 0 && (
        <Paper>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>PO number</TableCell>
                  <TableCell>Supplier</TableCell>
                  <TableCell>Date</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell align="right">Total</TableCell>
                  <TableCell align="right">Outstanding</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {openLoading ? (
                  <TableRow><TableCell colSpan={7}>Loading...</TableCell></TableRow>
                ) : openPurchases.length === 0 ? (
                  <TableRow><TableCell colSpan={7}>No open purchase orders. Create one with “Receive later” to see it here.</TableCell></TableRow>
                ) : openPurchases.map((purchase) => {
                  const outstanding = (purchase.items || []).reduce(
                    (sum, item) => sum + Math.max(0, (item.quantity || 0) - (item.receivedQuantity || 0)), 0
                  );
                  return (
                    <TableRow key={purchase.id} hover>
                      <TableCell>{purchase.purchaseNumber}</TableCell>
                      <TableCell>{purchase.supplierName}</TableCell>
                      <TableCell>{formatDate(purchase.purchaseDate)}</TableCell>
                      <TableCell>
                        <Chip size="small" label={purchase.receivingStatus} color={STATUS_COLOR[purchase.receivingStatus] || 'default'} />
                      </TableCell>
                      <TableCell align="right">{formatCurrency(purchase.totalAmount)}</TableCell>
                      <TableCell align="right">{outstanding}</TableCell>
                      <TableCell align="right">
                        <Button size="small" variant="contained" onClick={() => setReceivePurchase(purchase)}>Receive</Button>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableContainer>
        </Paper>
      )}

      {tab === 1 && (
        <Paper>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>PO number</TableCell>
                  <TableCell>Supplier</TableCell>
                  <TableCell>Date</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell align="right">Total</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {purchaseLoading ? (
                  <TableRow><TableCell colSpan={6}>Loading...</TableCell></TableRow>
                ) : purchases.length === 0 ? (
                  <TableRow><TableCell colSpan={6}>No received purchases found.</TableCell></TableRow>
                ) : purchases.map((purchase) => (
                  <TableRow key={purchase.id} hover>
                    <TableCell>{purchase.purchaseNumber}</TableCell>
                    <TableCell>{purchase.supplierName}</TableCell>
                    <TableCell>{formatDate(purchase.purchaseDate)}</TableCell>
                    <TableCell>{purchase.receivingStatus}</TableCell>
                    <TableCell align="right">{formatCurrency(purchase.totalAmount)}</TableCell>
                    <TableCell align="right">
                      <Button size="small" onClick={() => setRtvPurchase(purchase)}>Return</Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </Paper>
      )}

      <ReceiveDialog
        purchase={receivePurchase}
        locations={locations}
        open={Boolean(receivePurchase)}
        onClose={() => setReceivePurchase(null)}
      />
      <RtvDialog
        purchase={rtvPurchase}
        open={Boolean(rtvPurchase)}
        onClose={() => setRtvPurchase(null)}
      />
    </Box>
  );
};

export default Receiving;
