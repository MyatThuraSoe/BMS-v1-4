import { useState, useEffect } from 'react';
import { Button, MenuItem, TextField, Box, CircularProgress, Chip } from '@mui/material';
import PrintIcon from '@mui/icons-material/Print';
import { useSnackbar } from 'notistack';
import directPrint from '../services/directPrintService';

const DirectPrintButton = ({ getReceiptHtml, label = 'Direct Print' }) => {
    const { enqueueSnackbar } = useSnackbar();
    const [printers, setPrinters] = useState([]);
    const [printer, setPrinter] = useState(localStorage.getItem('lumipos_printer') || '');
    const [printing, setPrinting] = useState(false);

    useEffect(() => {
        if (!directPrint.isAvailable()) return;

        directPrint.getPrinters().then(list => {
            setPrinters(list);
            if (!printer && list.length > 0) {
                const def = list.find(p => p.isDefault) || list[0];
                setPrinter(def.name);
                localStorage.setItem('lumipos_printer', def.name);
            }
        });
    }, []);

    const handlePrint = async () => {
        setPrinting(true);
        try {
            const bodyHtml = getReceiptHtml();
            // Wrap in a complete HTML document optimized for thermal printers (80mm)
            const fullHtml = `<!DOCTYPE html>
<html><head><meta charset="utf-8"><style>
  @page { size: 80mm auto; margin: 0; }
  body {
    width: 72mm;
    margin: 0 auto;
    padding: 4mm;
    font-family: 'Courier New', monospace;
    font-size: 12px;
    color: #000;
    background: #fff;
  }
  table { width: 100%; border-collapse: collapse; }
  img { max-width: 100%; }
</style></head><body>${bodyHtml}</body></html>`;

            const result = await directPrint.print(fullHtml, printer);
            if (result.success) {
                enqueueSnackbar('Receipt sent to printer', { variant: 'success' });
            } else {
                enqueueSnackbar('Print failed: ' + result.error, { variant: 'error' });
            }
        } catch (e) {
            enqueueSnackbar(e.message, { variant: 'error' });
        } finally {
            setPrinting(false);
        }
    };

    // Don't render anything if running in a regular browser (not Electron)
    if (!directPrint.isAvailable()) return null;

    return (
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
            <TextField
                select
                size="small"
                label="Printer"
                value={printer}
                onChange={(e) => {
                    setPrinter(e.target.value);
                    localStorage.setItem('lumipos_printer', e.target.value);
                }}
                sx={{ minWidth: 200 }}
            >
                {printers.length === 0 && (
                    <MenuItem disabled>No printers found</MenuItem>
                )}
                {printers.map(p => (
                    <MenuItem key={p.name} value={p.name}>
                        {p.displayName} {p.isDefault && <Chip size="small" label="default" sx={{ ml: 1 }} />}
                    </MenuItem>
                ))}
            </TextField>
            <Button
                variant="contained"
                color="primary"
                startIcon={printing ? <CircularProgress size={16} sx={{ color: 'white' }} /> : <PrintIcon />}
                onClick={handlePrint}
                disabled={printing || !printer || printers.length === 0}
            >
                {label}
            </Button>
        </Box>
    );
};

export default DirectPrintButton;