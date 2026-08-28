import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Box, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, TextField, TablePagination, Dialog, DialogTitle, DialogContent, DialogActions, Chip, IconButton, CircularProgress, Stack, Divider,
} from '@mui/material';
import {
  Search as SearchIcon,
  Payments as PaymentsIcon,
  History as HistoryIcon,
  Print as PrintIcon,
  ReceiptLong as ReceiptIcon,
  Visibility as ViewIcon,
} from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { arService, receiptService, saleService, shopInfoService, receiptCustomizationService } from '../api/services';
import { formatCurrency, formatDateTime, formatDate } from '../utils/helpers';
import { notifyError } from '../utils/notify';
import { generatePrintHtml, generateQRDataUrl } from '../components/ReceiptDocument';

const AccountsReceivable = () => {
  const { t } = useTranslation('ar');
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [debouncedKeyword, setDebouncedKeyword] = useState('');

  const [payInvoice, setPayInvoice] = useState(null);
  const [payAmount, setPayAmount] = useState('');
  const [payNotes, setPayNotes] = useState('');
  const [payError, setPayError] = useState('');

  const [historyCustomer, setHistoryCustomer] = useState(null);
  const [historyPage, setHistoryPage] = useState(0);
  const [historySize, setHistorySize] = useState(20);

  const [detailInvoice, setDetailInvoice] = useState(null);

  const { data: outstandingData, isLoading } = useQuery({
    queryKey: ['ar-outstanding', page, size, debouncedKeyword],
    queryFn: () => arService.getOutstanding(page, size, debouncedKeyword),
  });

  const { data: historyData } = useQuery({
    queryKey: ['ar-history', historyCustomer?.customerId, historyPage, historySize],
    queryFn: () => arService.getCustomerHistory(historyCustomer.customerId, historyPage, historySize),
    enabled: !!historyCustomer,
  });

  const { data: detailData, isLoading: detailLoading } = useQuery({
    queryKey: ['ar-sale-detail', detailInvoice?.invoiceId],
    queryFn: () => saleService.getById(detailInvoice.invoiceId),
    enabled: !!detailInvoice,
  });

  const { data: paymentsData, isLoading: paymentsLoading } = useQuery({
    queryKey: ['ar-payments', detailInvoice?.invoiceId],
    queryFn: () => arService.getInvoicePayments(detailInvoice.invoiceId),
    enabled: !!detailInvoice,
  });

  const payMutation = useMutation({
    mutationFn: ({ invoiceId, data }) => arService.recordPayment(invoiceId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ar-outstanding'] });
      setPayInvoice(null);
      setPayAmount('');
      setPayNotes('');
    },
    onError: (err) => {
      setPayError(err?.response?.data?.message || t('payment_failed'));
    },
  });

  const rows = outstandingData?.data?.content || [];
  const totalElements = outstandingData?.data?.page?.totalElements || 0;
  const historyRows = historyData?.data?.content || [];
  const historyTotal = historyData?.data?.page?.totalElements || 0;

  const detailSale = detailData?.data || {};
  const detailPayments = paymentsData?.data || [];

  const handlePayOpen = (row) => {
    setPayInvoice(row);
    setPayAmount('');
    setPayNotes('');
    setPayError('');
  };

  const handlePayConfirm = () => {
    const amount = parseFloat(payAmount);
    if (!payAmount || isNaN(amount) || amount <= 0) {
      setPayError(t('enter_valid_amount'));
      return;
    }
    if (amount > Number(payInvoice.balanceDue)) {
      setPayError(t('amount_exceeds_balance'));
      return;
    }
    payMutation.mutate({ invoiceId: payInvoice.invoiceId, data: { amount, notes: payNotes } });
  };

  // ── Printing helpers (open a window synchronously to avoid popup blockers) ──

  const writeHtmlAndPrint = (win, html) => {
    win.document.open();
    win.document.write(html);
    win.document.close();
    win.focus();
    setTimeout(() => { try { win.print(); } catch { /* ignore */ } }, 250);
  };

  const handlePrintInvoice = async (invoiceNumber) => {
    const win = window.open('', '_blank');
    try {
      const [receiptResponse, shopInfoResponse, customizationResponse] = await Promise.all([
        receiptService.getByInvoiceNumber(invoiceNumber),
        shopInfoService.get(),
        receiptCustomizationService.get(),
      ]);
      const receipt = receiptResponse?.data || {};
      const shopInfo = shopInfoResponse?.data || {};
      const customization = customizationResponse?.data || {};
      let logoDataUrl = null;
      if (shopInfo.hasLogo) {
        try {
          const logoBlob = await shopInfoService.getLogo();
          logoDataUrl = await new Promise((resolve) => {
            const reader = new FileReader();
            reader.onload = () => resolve(reader.result);
            reader.onerror = () => resolve(null);
            reader.readAsDataURL(logoBlob);
          });
        } catch { /* print without logo */ }
      }
      const qrDataUrl = customization.showQRCode
        ? await generateQRDataUrl(receipt.invoiceNumber)
        : null;
      const html = generatePrintHtml(receipt, shopInfo, customization, logoDataUrl, qrDataUrl);
      if (win) writeHtmlAndPrint(win, html);
      else notifyError(t('print_blocked'));
    } catch {
      if (win) win.close();
      notifyError(t('print_failed'));
    }
  };

  const handlePrintPayment = async (paymentId) => {
    const win = window.open('', '_blank');
    try {
      const html = await arService.getPaymentPrintHtml(paymentId);
      if (win) writeHtmlAndPrint(win, html);
      else notifyError(t('print_blocked'));
    } catch {
      if (win) win.close();
      notifyError(t('print_failed'));
    }
  };

  const today = new Date();

  return (
    <Box>
      
      <Paper sx={{ mb: 2, p: 2 }}>
        <TextField
          sx={{ flexGrow: 1, minWidth: 220, width: { xs: '100%', md: 360 } }}
          placeholder={t('search_placeholder')}
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value);
            setPage(0);
            setTimeout(() => setDebouncedKeyword(e.target.value), 300);
          }}
          InputProps={{ startAdornment: <SearchIcon sx={{ mr: 1, color: 'text.secondary' }} /> }}
          size="small"
        />
      </Paper>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>{t('invoice')}</TableCell>
              <TableCell>{t('customer')}</TableCell>
              <TableCell align="right">{t('total')}</TableCell>
              <TableCell align="right">{t('paid')}</TableCell>
              <TableCell align="right">{t('balance')}</TableCell>
              <TableCell>{t('due_date')}</TableCell>
              <TableCell>{t('status')}</TableCell>
              <TableCell align="right">{t('actions')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={8} align="center">{t('loading')}</TableCell></TableRow>
            ) : rows.length === 0 ? (
              <TableRow><TableCell colSpan={8} align="center">{t('no_outstanding')}</TableCell></TableRow>
            ) : (
              rows.map((row) => {
                const overdue = row.paymentStatus !== 'PAID' && row.dueDate && new Date(row.dueDate) < today;
                return (
                  <TableRow
                    key={row.invoiceId}
                    hover
                    onClick={() => setDetailInvoice(row)}
                    sx={{ cursor: 'pointer' }}
                  >
                    <TableCell>
                      <Stack direction="row" spacing={0.5} alignItems="center">
                        <ViewIcon fontSize="small" sx={{ color: 'primary.main' }} />
                        <span>{row.invoiceNumber}</span>
                      </Stack>
                    </TableCell>
                    <TableCell>{row.customerName || '-'}</TableCell>
                    <TableCell align="right">{formatCurrency(row.totalAmount)}</TableCell>
                    <TableCell align="right">{formatCurrency(row.amountPaid)}</TableCell>
                    <TableCell align="right">
                      <Chip
                        size="small"
                        color={overdue ? 'error' : row.paymentStatus === 'PAID' ? 'success' : 'warning'}
                        label={formatCurrency(row.balanceDue)}
                      />
                    </TableCell>
                    <TableCell>
                      {row.dueDate ? formatDate(row.dueDate) : '-'}
                      {overdue && <span> · {t('overdue')}</span>}
                    </TableCell>
                    <TableCell>
                      <Chip size="small" label={t(`payment_status_${row.paymentStatus}`)} color={row.paymentStatus === 'PAID' ? 'success' : 'warning'} />
                    </TableCell>
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }} onClick={(e) => e.stopPropagation()}>
                      {row.paymentStatus !== 'PAID' && (
                        <Button size="small" variant="contained" startIcon={<PaymentsIcon />} onClick={() => handlePayOpen(row)}>
                          {t('record_payment')}
                        </Button>
                      )}
                      <IconButton size="small" onClick={() => { setHistoryCustomer(row); setHistoryPage(0); }} title={t('customer_history')}>
                        <HistoryIcon />
                      </IconButton>
                    </TableCell>
                  </TableRow>
                );
              })
            )}
          </TableBody>
        </Table>
        <TablePagination
          component="div"
          count={totalElements}
          page={page}
          rowsPerPage={size}
          onPageChange={(e, newPage) => setPage(newPage)}
          onRowsPerPageChange={(e) => { setSize(parseInt(e.target.value)); setPage(0); }}
          rowsPerPageOptions={[10, 20, 50]}
        />
      </TableContainer>

      {/* Record Payment Dialog */}
      <Dialog open={!!payInvoice} onClose={() => setPayInvoice(null)}>
        <DialogTitle>{t('record_payment')}</DialogTitle>
        <DialogContent>
          {payInvoice && (
            <>
              <Typography>{t('invoice')}: {payInvoice.invoiceNumber}</Typography>
              <Typography>{t('customer')}: {payInvoice.customerName}</Typography>
              <Typography>{t('balance_due')}: {formatCurrency(payInvoice.balanceDue)}</Typography>
              <TextField
                fullWidth
                label={t('amount')}
                type="number"
                value={payAmount}
                onChange={(e) => setPayAmount(e.target.value)}
                sx={{ mt: 2 }}
                autoFocus
              />
              <TextField
                fullWidth
                label={t('notes')}
                value={payNotes}
                onChange={(e) => setPayNotes(e.target.value)}
                multiline
                rows={2}
                sx={{ mt: 2 }}
              />
              {payError && <Typography color="error" sx={{ mt: 1 }}>{payError}</Typography>}
            </>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPayInvoice(null)}>{t('cancel')}</Button>
          <Button onClick={handlePayConfirm} variant="contained" color="primary" disabled={payMutation.isPending}>
            {t('confirm_payment')}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Customer History Dialog */}
      <Dialog open={!!historyCustomer} onClose={() => setHistoryCustomer(null)} maxWidth="md" fullWidth>
        <DialogTitle>
          {t('customer_history_title', { name: historyCustomer?.customerName || '' })}
        </DialogTitle>
        <DialogContent>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t('invoice')}</TableCell>
                  <TableCell align="right">{t('total')}</TableCell>
                  <TableCell align="right">{t('balance')}</TableCell>
                  <TableCell>{t('sale_date')}</TableCell>
                  <TableCell>{t('due_date')}</TableCell>
                  <TableCell>{t('status')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {historyRows.length === 0 ? (
                  <TableRow><TableCell colSpan={6} align="center">{t('no_history')}</TableCell></TableRow>
                ) : (
                  historyRows.map((row) => (
                    <TableRow
                      key={row.invoiceId}
                      hover
                      onClick={() => { setHistoryCustomer(null); setDetailInvoice(row); }}
                      sx={{ cursor: 'pointer' }}
                    >
                      <TableCell>{row.invoiceNumber}</TableCell>
                      <TableCell align="right">{formatCurrency(row.totalAmount)}</TableCell>
                      <TableCell align="right">{formatCurrency(row.balanceDue)}</TableCell>
                      <TableCell>{row.saleDate ? formatDateTime(row.saleDate) : '-'}</TableCell>
                      <TableCell>{row.dueDate ? formatDate(row.dueDate) : '-'}</TableCell>
                      <TableCell>
                        <Chip size="small" label={t(`payment_status_${row.paymentStatus}`)} color={row.paymentStatus === 'PAID' ? 'success' : 'warning'} />
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination
            component="div"
            count={historyTotal}
            page={historyPage}
            rowsPerPage={historySize}
            onPageChange={(e, newPage) => setHistoryPage(newPage)}
            onRowsPerPageChange={(e) => { setHistorySize(parseInt(e.target.value)); setHistoryPage(0); }}
            rowsPerPageOptions={[10, 20, 50]}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setHistoryCustomer(null)}>{t('close')}</Button>
        </DialogActions>
      </Dialog>

      {/* Invoice Details Dialog */}
      <Dialog open={!!detailInvoice} onClose={() => setDetailInvoice(null)} maxWidth="md" fullWidth>
        <DialogTitle>
          <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={1} flexWrap="wrap">
            <Stack direction="row" spacing={1} alignItems="center">
              <ReceiptIcon sx={{ color: 'primary.main' }} />
              <Typography variant="h6">{t('invoice_details')}</Typography>
              <Chip size="small" label={detailInvoice?.invoiceNumber} color="primary" />
            </Stack>
            <Button size="small" variant="outlined" startIcon={<PrintIcon />} onClick={() => handlePrintInvoice(detailInvoice?.invoiceNumber)}>
              {t('print_invoice_receipt')}
            </Button>
          </Stack>
        </DialogTitle>
        <DialogContent dividers>
          {detailLoading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}><CircularProgress /></Box>
          ) : (
            <Stack spacing={3}>
              {/* Summary */}
              <Box>
                <Typography variant="subtitle2" fontWeight={700} gutterBottom>{t('sale_details')}</Typography>
                <Stack direction="row" spacing={1} flexWrap="wrap" sx={{ mb: 1.5 }}>
                  <Chip
                    size="small"
                    color={detailSale.paymentStatus === 'PAID' ? 'success' : 'warning'}
                    label={t(`payment_status_${detailSale.paymentStatus}`)}
                  />
                  {detailSale.saleType && (
                    <Chip size="small" variant="outlined" label={t(`sale_type_${detailSale.saleType}`)} />
                  )}
                </Stack>
                <Stack spacing={0.5}>
                  <Typography variant="body2">
                    <strong>{t('customer')}:</strong> {detailSale.customerName || '-'}
                  </Typography>
                  <Typography variant="body2">
                    <strong>{t('sale_date')}:</strong> {detailSale.saleDate ? formatDateTime(detailSale.saleDate) : '-'}
                  </Typography>
                  <Typography variant="body2">
                    <strong>{t('due_date')}:</strong> {detailSale.dueDate ? formatDate(detailSale.dueDate) : '-'}
                  </Typography>
                  <Typography variant="body2">
                    <strong>{t('cashier')}:</strong> {detailSale.cashierName || '-'}
                  </Typography>
                </Stack>
              </Box>

              <Divider />

              {/* Items */}
              <Box>
                <Typography variant="subtitle2" fontWeight={700} gutterBottom>{t('items')}</Typography>
                <TableContainer>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>{t('product')}</TableCell>
                        <TableCell align="right">{t('qty')}</TableCell>
                        <TableCell align="right">{t('unit_price')}</TableCell>
                        <TableCell align="right">{t('amount')}</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {(detailSale.items || []).map((item, idx) => (
                        <TableRow key={item.id || idx}>
                          <TableCell>{item.productName}</TableCell>
                          <TableCell align="right">{item.quantity}</TableCell>
                          <TableCell align="right">{formatCurrency(item.unitPrice)}</TableCell>
                          <TableCell align="right">{formatCurrency(item.totalPrice)}</TableCell>
                        </TableRow>
                      ))}
                      {(detailSale.items || []).length === 0 && (
                        <TableRow><TableCell colSpan={4} align="center">{t('no_items')}</TableCell></TableRow>
                      )}
                    </TableBody>
                  </Table>
                </TableContainer>
                <Stack spacing={0.5} sx={{ mt: 1.5, alignItems: 'flex-end' }}>
                  {Number(detailSale.subtotal) > 0 && (
                    <Typography variant="body2"><strong>{t('subtotal')}:</strong> {formatCurrency(detailSale.subtotal)}</Typography>
                  )}
                  {Number(detailSale.taxAmount) > 0 && (
                    <Typography variant="body2"><strong>{t('tax')}:</strong> {formatCurrency(detailSale.taxAmount)}</Typography>
                  )}
                  {Number(detailSale.discountAmount) > 0 && (
                    <Typography variant="body2"><strong>{t('discount')}:</strong> -{formatCurrency(detailSale.discountAmount)}</Typography>
                  )}
                  <Typography variant="body1" fontWeight={700}><strong>{t('total')}:</strong> {formatCurrency(detailSale.totalAmount)}</Typography>
                  <Typography variant="body2"><strong>{t('paid')}:</strong> {formatCurrency(detailSale.amountPaid)}</Typography>
                  <Typography variant="body2" fontWeight={600} color="error"><strong>{t('balance_due')}:</strong> {formatCurrency(detailInvoice?.balanceDue ?? (Number(detailSale.totalAmount || 0) - Number(detailSale.amountPaid || 0)))}</Typography>
                </Stack>
              </Box>

              <Divider />

              {/* Payment History */}
              <Box>
                <Typography variant="subtitle2" fontWeight={700} gutterBottom>{t('payment_history')}</Typography>
                {paymentsLoading ? (
                  <Box sx={{ display: 'flex', justifyContent: 'center', py: 3 }}><CircularProgress size={28} /></Box>
                ) : detailPayments.length === 0 ? (
                  <Typography variant="body2" color="text.secondary">{t('no_payments')}</Typography>
                ) : (
                  <TableContainer>
                    <Table size="small">
                      <TableHead>
                        <TableRow>
                          <TableCell>{t('payment_datetime')}</TableCell>
                          <TableCell align="right">{t('amount')}</TableCell>
                          <TableCell>{t('recorded_by')}</TableCell>
                          <TableCell>{t('notes')}</TableCell>
                          <TableCell align="right">{t('print')}</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {detailPayments.map((p) => (
                          <TableRow key={p.id}>
                            <TableCell>{p.paymentDate ? formatDateTime(p.paymentDate) : '-'}</TableCell>
                            <TableCell align="right">{formatCurrency(p.amount)}</TableCell>
                            <TableCell>{p.recordedByName || '-'}</TableCell>
                            <TableCell>{p.notes || '-'}</TableCell>
                            <TableCell align="right">
                              <IconButton size="small" onClick={() => handlePrintPayment(p.id)} title={t('print_payment_receipt')}>
                                <PrintIcon />
                              </IconButton>
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                )}
              </Box>
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          {detailInvoice?.paymentStatus !== 'PAID' && (
            <Button startIcon={<PaymentsIcon />} onClick={() => { handlePayOpen(detailInvoice); setDetailInvoice(null); }}>
              {t('record_payment')}
            </Button>
          )}
          <Button onClick={() => setDetailInvoice(null)}>{t('close')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default AccountsReceivable;