import { useState, useRef, useEffect } from 'react';
import {
    Box, Typography, Paper, Button, Stack, Alert, Chip, Divider,
    Dialog, DialogTitle, DialogContent, DialogActions,
    FormControlLabel, Checkbox, CircularProgress,
} from '@mui/material';
import {
    Download as DownloadIcon, UploadFile as UploadIcon,
} from '@mui/icons-material';
import { dataService } from '../api/services';
import { notifySuccess, notifyError } from '../utils/notify';
import { useTranslation } from 'react-i18next';
import HelpTip from '../components/HelpTip';

const DataManagement = () => {
    const { t } = useTranslation(['settings', 'common']);
    const [exporting, setExporting] = useState(false);
    const [importing, setImporting] = useState(false);
    const [preview, setPreview] = useState(null);
    const [fileName, setFileName] = useState('');
    const [mode, setMode] = useState('MERGE');
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [confirmChecked, setConfirmChecked] = useState(false);
    const [sameShopChecked, setSameShopChecked] = useState(false);
    const [localInstallId, setLocalInstallId] = useState('');
    const fileInputRef = useRef();

    // Which install is this? A backup file can name the one it came out of, but
    // only the backend knows the id of the one standing here, and the difference
    // between those two answers is the whole warning below.
    useEffect(() => {
        dataService.installId().then(setLocalInstallId).catch(() => {});
    }, []);
    const fileProvesSameShop = !!localInstallId && !!preview?.installId
        && preview.installId === localInstallId;

    // ---------- EXPORT ----------
    const handleExport = async () => {
        setExporting(true);
        try {
            const res = await dataService.exportAll();
            const blob = new Blob([res.data], { type: 'application/json' });
            const url = window.URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = `lumipos-backup-${new Date().toISOString().slice(0, 10)}.json`;
            link.click();
            window.URL.revokeObjectURL(url);
            notifySuccess(t('export_success'));
        } catch (err) {
            notifyError(t('export_failed'));
        } finally {
            setExporting(false);
        }
    };

    // ---------- IMPORT ----------
    const handleFileSelect = async (e) => {
        const file = e.target.files[0];
        if (!file) return;
        try {
            const text = await file.text();
            const json = JSON.parse(text);
            if (!json.data) throw new Error('Invalid backup');
            setPreview(json);
            setFileName(file.name);
            setSameShopChecked(false);
            notifySuccess(t('backup_loaded_from', { date: json.exportedAt || t('unknown_date') }));
        } catch {
            notifyError(t('invalid_backup_file'));
            setPreview(null);
        }
        e.target.value = '';
    };

    const startImport = () => {
        if (!preview) return;
        if (mode === 'REPLACE_ALL') {
            setConfirmChecked(false);
            setConfirmOpen(true);
        } else {
            doImport();
        }
    };

    async function doImport() {
        setConfirmOpen(false);
        setImporting(true);
        try {
            const res = await dataService.importAll(preview, mode, mode === 'MERGE' && sameShopChecked);
            const counts = res.data.data.counts;
            const summary = Object.entries(counts).map(([k, v]) => `${k}: ${v}`).join(', ');
            notifySuccess(t('import_complete', { summary }));
            setPreview(null);
            setFileName('');
            setSameShopChecked(false);
        } catch (err) {
            notifyError(err.response?.data?.message || t('import_failed'));
        } finally {
            setImporting(false);
        }
    }

    return (
        <Box sx={{ mt: 3 }}>
            {/* ================= EXPORT CARD ================= */}
            <Paper elevation={0} sx={{ p: 3, mb: 3, border: '1px solid', borderColor: 'divider', borderRadius: 3 }}>
                <Typography variant="h6" gutterBottom>
                    {t('export_backup')}
                    <HelpTip helpKey="help_local_export" t={t} />
                </Typography>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                    {t('export_backup_description')}
                </Typography>
                <Button
                    variant="contained"
                    size="large"
                    startIcon={exporting ? <CircularProgress size={20} sx={{ color: 'white' }} /> : <DownloadIcon />}
                    onClick={handleExport}
                    disabled={exporting}
                >
                    {exporting ? t('exporting') : t('export_all_data_json')}
                </Button>
            </Paper>

            {/* ================= IMPORT CARD ================= */}
            <Paper elevation={0} sx={{ p: 3, border: '1px solid', borderColor: 'divider', borderRadius: 3 }}>
                <Typography variant="h6" gutterBottom>
                    {t('import_restore_backup')}
                    <HelpTip helpKey="help_local_import" t={t} />
                </Typography>

                <input
                    type="file"
                    accept=".json,application/json"
                    ref={fileInputRef}
                    style={{ display: 'none' }}
                    onChange={handleFileSelect}
                />
                <Button variant="outlined" startIcon={<UploadIcon />} onClick={() => fileInputRef.current?.click()}>
                    {t('choose_backup_file')}
                </Button>

                {preview && (
                    <>
                        <Alert severity="info" sx={{ mt: 2 }}>
                            <strong>{fileName}</strong> — {t('exported_at', { date: preview.exportedAt || t('unknown') })}
                        </Alert>

                        {/* Preview counts */}
                        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mt: 2 }}>
                            {Object.entries(preview.data || {}).map(([key, arr]) => (
                                <Chip key={key} label={`${key}: ${Array.isArray(arr) ? arr.length : 0}`} variant="outlined" />
                            ))}
                        </Box>

                        <Divider sx={{ my: 2 }} />

                        {/* Mode selector */}
                        <Typography variant="subtitle2" sx={{ mb: 1 }}>{t('import_mode')}</Typography>
                        <Stack direction="row" spacing={1}>
                            <Button
                                variant={mode === 'MERGE' ? 'contained' : 'outlined'}
                                color="primary"
                                onClick={() => setMode('MERGE')}
                            >
                                {t('merge_safe')}
                            </Button>
                            <Button
                                variant={mode === 'REPLACE_ALL' ? 'contained' : 'outlined'}
                                color="error"
                                onClick={() => setMode('REPLACE_ALL')}
                            >
                                {t('replace_everything')}
                            </Button>
                        </Stack>
                        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
                            {mode === 'MERGE'
                                ? t('merge_mode_description')
                                : t('replace_mode_description')}
                        </Typography>

                        {mode === 'MERGE' && !fileProvesSameShop && (
                            <Alert severity="warning" sx={{ mt: 2 }}>
                                <Typography variant="body2" gutterBottom>
                                    {preview.installId
                                        ? t('merge_other_shop_warning')
                                        : t('merge_older_export_warning')}
                                </Typography>
                                <FormControlLabel
                                    control={
                                        <Checkbox
                                            checked={sameShopChecked}
                                            onChange={(e) => setSameShopChecked(e.target.checked)}
                                        />
                                    }
                                    label={t('merge_same_shop_confirm')}
                                />
                                <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                                    {t('merge_same_shop_confirm_hint')}
                                </Typography>
                            </Alert>
                        )}

                        <Button
                            fullWidth
                            size="large"
                            variant="contained"
                            color={mode === 'REPLACE_ALL' ? 'error' : 'primary'}
                            sx={{ mt: 2, py: 1.4 }}
                            startIcon={importing ? <CircularProgress size={20} sx={{ color: 'white' }} /> : <UploadIcon />}
                            onClick={startImport}
                            disabled={importing}
                        >
                            {importing ? t('importing') : mode === 'MERGE' ? t('start_merge_import') : t('replace_all_and_import')}
                        </Button>
                    </>
                )}
            </Paper>

            {/* ============ REPLACE_ALL CONFIRMATION DIALOG ============ */}
            <Dialog open={confirmOpen} onClose={() => setConfirmOpen(false)}>
                <DialogTitle>{t('replace_all_title')}</DialogTitle>
                <DialogContent>
                    <Alert severity="error" sx={{ mb: 2 }}>
                        {t('replace_all_warning')}
                    </Alert>
                    <Typography variant="body2">
                        {t('replace_all_tip')}
                    </Typography>
                    <FormControlLabel
                        sx={{ mt: 2 }}
                        control={
                            <Checkbox
                                checked={confirmChecked}
                                onChange={(e) => setConfirmChecked(e.target.checked)}
                            />
                        }
                        label={t('confirm_irreversible')}
                    />
                </DialogContent>
                <DialogActions>
                    <Button onClick={() => setConfirmOpen(false)}>{t('cancel')}</Button>
                    <Button variant="contained" color="error" disabled={!confirmChecked} onClick={doImport}>
                        {t('yes_replace_everything')}
                    </Button>
                </DialogActions>
            </Dialog>
        </Box>
    );
};

export default DataManagement;