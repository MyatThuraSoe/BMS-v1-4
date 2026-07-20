import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Box, Button, Checkbox, Chip, FormControl, Grid, InputLabel, MenuItem, Paper, Select, TextField, Typography } from '@mui/material';
import { productService } from '../api/services';
import ProtectedRoute from '../components/ProtectedRoute';

// Note: This page loads jsbarcode from a CDN at runtime to avoid npm install issues in this environment.
// It renders into SVG for scannability.
function useJsBarcode() {
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const existing = window.document.getElementById('jsbarcode-script');
    if (existing) {
      setReady(true);
      return;
    }

    const script = window.document.createElement('script');
    script.id = 'jsbarcode-script';
    script.src = 'https://cdn.jsdelivr.net/npm/jsbarcode@3.11.6/dist/JsBarcode.all.min.js';
    script.async = true;
    script.onload = () => setReady(true);
    script.onerror = () => setReady(false);
    window.document.body.appendChild(script);

    return () => {};
  }, []);

  return ready;
}

function BarcodeSvg({ value, width = 2, height = 60, fontSize = 12 }) {
  const ref = useRef(null);
  const jsBarcodeReady = typeof window !== 'undefined' && window.JsBarcode;

  useEffect(() => {
    if (!jsBarcodeReady) return;
    if (!ref.current) return;
    if (!value) return;

    try {
      // @ts-ignore
      window.JsBarcode(ref.current, String(value), {
        format: 'CODE128',
        displayValue: true,
        margin: 0,
        width,
        height,
        fontSize,
        textAlign: 'center',
      });
    } catch (e) {
      // eslint-disable-next-line no-console
      console.error('Failed to render barcode', e);
    }
  }, [value, width, height, fontSize, jsBarcodeReady]);

  return <svg ref={ref} />;
}

function LabelsPreviewGrid({ items, labelHeightPx, cols }) {
  // items: [{ product, qty }]
  const rendered = [];
  for (const item of items) {
    for (let i = 0; i < item.qty; i++) {
      rendered.push({ key: `${item.product.id}-${i}`, product: item.product });
    }
  }

  return (
    <Box className="print-labels">
      <Box
        className="label-grid"
        sx={{
          gridTemplateColumns: `repeat(${cols}, 1fr)`,
          ['--labelHeightPx']: `${labelHeightPx}px`,
        }}
      >
        {rendered.map((r) => (
          <Box key={r.key} className="label-cell">
            <Box className="label-title">{r.product.name}</Box>
            <Box className="label-price">{Number(r.product.unitPrice).toFixed(2)}</Box>
            <Box className="label-barcode">
              <BarcodeSvg value={r.product.barcode} width={2} height={40} fontSize={10} />
            </Box>
          </Box>
        ))}
      </Box>
    </Box>
  );
}

function BarcodeLabelsPage() {
  const jsBarcodeReady = useJsBarcode();

  const [products, setProducts] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [keyword, setKeyword] = useState('');

  // selections: productId -> qty
  const [selected, setSelected] = useState({});
  const [labelsPerProduct, setLabelsPerProduct] = useState(5);

  // Simple layout defaults: 30-ish labels per sheet is common, but exact sheet size varies.
  // Start with 4 columns as a reasonable default for quick testing; adjust later.
  const [cols, setCols] = useState(4);
  const [labelHeightPx, setLabelHeightPx] = useState(140);

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      setLoadError('');
      try {
        const resp = await productService.getAll(0, 5000, 'createdAt', null);
        const data = resp?.data ?? resp; // backend ApiResponse wrapping varies
        setProducts(Array.isArray(data?.data) ? data.data : Array.isArray(data) ? data : []);
      } catch (e) {
        setLoadError('Failed to load products');
      } finally {
        setLoading(false);
      }
    };
    load();
  }, []);

  const filteredProducts = useMemo(() => {
    if (!keyword.trim()) return products;
    const k = keyword.trim().toLowerCase();
    return products.filter((p) => (p.name || '').toLowerCase().includes(k) || (p.barcode || '').toLowerCase().includes(k));
  }, [products, keyword]);

  const selectedItems = useMemo(() => {
    const map = new Map(products.map((p) => [p.id, p]));
    return Object.entries(selected)
      .filter(([, qty]) => qty > 0)
      .map(([id, qty]) => ({ product: map.get(Number(id)), qty }))
      .filter((x) => x.product);
  }, [selected, products]);

  const canPreview = jsBarcodeReady && selectedItems.length > 0;

  const applyQtyToAllSelected = () => {
    setSelected((prev) => {
      const next = { ...prev };
      for (const k of Object.keys(next)) {
        if (next[k] > 0) next[k] = labelsPerProduct;
      }
      return next;
    });
  };

  const toggleProduct = (productId) => {
    setSelected((prev) => {
      const next = { ...prev };
      if (next[productId] && next[productId] > 0) {
        delete next[productId];
      } else {
        next[productId] = labelsPerProduct;
      }
      return next;
    });
  };

  const setQty = (productId, qty) => {
    const n = Math.max(0, Number(qty) || 0);
    setSelected((prev) => ({ ...prev, [productId]: n }));
  };

  const onPrint = () => {
    if (!jsBarcodeReady) return;
    if (!selectedItems.length) return;
    window.print();
  };

  return (
    <ProtectedRoute allowedRoles={['ADMIN', 'MANAGER']}>
      <Box>
        <Typography variant="h5" gutterBottom>
          Barcode Labels
        </Typography>

        {!jsBarcodeReady && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            Loading barcode renderer…
          </Alert>
        )}

        {loadError && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {loadError}
          </Alert>
        )}

        <Paper sx={{ p: 2, mb: 2 }}>
          <Grid container spacing={2} alignItems="flex-end">
            <Grid item xs={12} sm={6}>
              <TextField
                label="Search products (name or barcode)"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                fullWidth
                size="small"
              />
            </Grid>

            <Grid item xs={12} sm={3}>
              <FormControl size="small" fullWidth>
                <InputLabel id="labels-per-product-label">Labels per product</InputLabel>
                <Select
                  labelId="labels-per-product-label"
                  label="Labels per product"
                  value={labelsPerProduct}
                  onChange={(e) => setLabelsPerProduct(Number(e.target.value))}
                >
                  {[1, 2, 3, 4, 5, 10, 20].map((n) => (
                    <MenuItem key={n} value={n}>
                      {n}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Grid>

            <Grid item xs={12} sm={3}>
              <Button variant="outlined" onClick={applyQtyToAllSelected} disabled={!Object.keys(selected).length}>
                Apply qty to selected
              </Button>
            </Grid>

            <Grid item xs={12} sm={3}>
              <FormControl size="small" fullWidth>
                <InputLabel id="cols-label">Preview columns</InputLabel>
                <Select labelId="cols-label" label="Preview columns" value={cols} onChange={(e) => setCols(Number(e.target.value))}>
                  {[2, 3, 4, 6].map((n) => (
                    <MenuItem key={n} value={n}>
                      {n} cols
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Grid>

            <Grid item xs={12} sm={3}>
              <TextField
                label="Label height (px)"
                type="number"
                size="small"
                value={labelHeightPx}
                onChange={(e) => setLabelHeightPx(Number(e.target.value))}
                fullWidth
              />
            </Grid>

            <Grid item xs={12} sm={6} display="flex" justifyContent="flex-end">
              <Button variant="contained" onClick={onPrint} disabled={!canPreview}>
                Print
              </Button>
            </Grid>

            <Grid item xs={12}>
              <Typography variant="body2" color="text.secondary">
                Selected labels: <b>{selectedItems.reduce((sum, x) => sum + x.qty, 0)}</b>
              </Typography>
            </Grid>
          </Grid>
        </Paper>

        <Paper sx={{ p: 2, mb: 2 }}>
          <Typography variant="subtitle1" gutterBottom>
            Select products
          </Typography>

          <Grid container spacing={1}>
            {filteredProducts.slice(0, 200).map((p) => {
              const qty = selected[p.id] || 0;
              const checked = qty > 0;
              return (
                <Grid key={p.id} item xs={12} md={6} lg={4}>
                  <Box
                    sx={{
                      border: '1px solid',
                      borderColor: checked ? 'primary.main' : 'divider',
                      borderRadius: 1,
                      p: 1,
                      display: 'flex',
                      alignItems: 'center',
                      gap: 1,
                    }}
                  >
                    <Checkbox checked={checked} onChange={() => toggleProduct(p.id)} />
                    <Box sx={{ flex: 1, minWidth: 0 }}>
                      <Typography variant="body2" sx={{ fontWeight: 600 }} noWrap>
                        {p.name}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" noWrap>
                        Barcode: {p.barcode || '—'}
                      </Typography>
                    </Box>
                    <TextField
                      label="Qty"
                      size="small"
                      type="number"
                      value={checked ? qty : 0}
                      onChange={(e) => setQty(p.id, e.target.value)}
                      disabled={!checked}
                      sx={{ width: 100 }}
                    />
                  </Box>
                </Grid>
              );
            })}
          </Grid>

          {loading && <Typography sx={{ mt: 2 }}>Loading products…</Typography>}
          {!loading && filteredProducts.length === 0 && (
            <Typography sx={{ mt: 2 }} color="text.secondary">
              No products found.
            </Typography>
          )}
        </Paper>

        <Paper sx={{ p: 2 }}>
          <Typography variant="subtitle1" gutterBottom>
            Print preview
          </Typography>
          {!canPreview ? (
            <Typography color="text.secondary">Select products to preview labels.</Typography>
          ) : (
            <LabelsPreviewGrid items={selectedItems} labelHeightPx={labelHeightPx} cols={cols} />
          )}
        </Paper>
      </Box>

      {/* Print styles */}
      <style>{`
        @media print {
          body { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
          /* Hide everything except print-labels */
          body > * { visibility: hidden; }
          .print-labels, .print-labels * { visibility: visible; }
          .print-labels { position: absolute; left: 0; top: 0; }
        }

        .label-grid {
          display: grid;
          gap: 8px;
          /* columns set inline */
        }

        .label-cell {
          border: 1px solid #000;
          padding: 6px;
          height: var(--labelHeightPx);
          box-sizing: border-box;
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: flex-start;
        }

        .label-title {
          font-size: 12px;
          font-weight: 700;
          text-align: center;
          margin-bottom: 2px;
          line-height: 1.1;
          height: 28px;
          overflow: hidden;
          width: 100%;
        }

        .label-price {
          font-size: 12px;
          font-weight: 600;
          margin-bottom: 4px;
        }

        .label-barcode svg {
          width: 100%;
          height: auto;
        }
      `}</style>
    </ProtectedRoute>
  );
}

export default BarcodeLabelsPage;
