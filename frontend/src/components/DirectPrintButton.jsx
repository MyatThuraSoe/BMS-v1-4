import { useState, useEffect } from 'react';
import { Button, MenuItem, TextField, Box, CircularProgress, Chip } from '@mui/material';
import PrintIcon from '@mui/icons-material/Print';
import { useSnackbar } from 'notistack';
import { useTranslation } from 'react-i18next';
import directPrint from '../services/directPrintService';

/**
 * DirectPrintButton
 *
 * Props:
 *   getReceiptHtml  – function that returns the receipt body HTML string
 *   label           – button label
 *   paperSizeMm     – paper width in mm (default 80). Must match the receipt customization setting.
 */
const DirectPrintButton = ({ getReceiptHtml, label = 'Direct Print', paperSizeMm = 80 }) => {
    const { t } = useTranslation('common');
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
            const mm = Math.max(40, paperSizeMm || 80);
            const fullHtml = `<!DOCTYPE html>
<html><head><meta charset="utf-8"><style>
  @page { size: ${mm}mm auto; margin: 0; }
  body {
    width: ${mm - 6}mm;
    margin: 0 auto;
    padding: 3mm 3mm 8mm 3mm;
    font-family: 'Courier New', monospace;
    font-size: 13px;
    color: #000;
    background: #fff;
  }
  table { width: 100%; border-collapse: collapse; }
  img { max-width: 100%; }
</style></head><body>${bodyHtml}</body></html>`;

            const result = await directPrint.print(fullHtml, printer, mm);
            if (result.success) {
                enqueueSnackbar(t('sent_printer'), { variant: 'success' });
            } else {
                enqueueSnackbar(t('print_failed') + ': ' + result.error, { variant: 'error' });
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
                label={t('printer')}
                value={printer}
                onChange={(e) => {
                    setPrinter(e.target.value);
                    localStorage.setItem('lumipos_printer', e.target.value);
                }}
                sx={{ minWidth: 200 }}
            >
                {printers.length === 0 && (
                    <MenuItem disabled>{t('no_printers')}</MenuItem>
                )}
                {printers.map(p => (
                    <MenuItem key={p.name} value={p.name}>
                        {p.displayName} {p.isDefault && <Chip size="small" label={t('default_chip')} sx={{ ml: 1 }} />}
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