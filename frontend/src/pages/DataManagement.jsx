import { useState, useRef } from 'react';
import {
    Box, Typography, Paper, Button, Stack, Alert, Chip, Divider,
    Dialog, DialogTitle, DialogContent, DialogActions,
    FormControlLabel, Checkbox, CircularProgress,
} from '@mui/material';
import {
    Download as DownloadIcon, UploadFile as UploadIcon, Storage as StorageIcon,
} from '@mui/icons-material';
import { dataService } from '../api/services';
import { notifySuccess, notifyError } from '../utils/notify';

const DataManagement = () => {
    const [exporting, setExporting] = useState(false);
    const [importing, setImporting] = useState(false);
    const [preview, setPreview] = useState(null);
    const [fileName, setFileName] = useState('');
    const [mode, setMode] = useState('MERGE');
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [confirmChecked, setConfirmChecked] = useState(false);
    const fileInputRef = useRef();

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
            notifySuccess('✅ Backup exported successfully');
        } catch (err) {
            notifyError('Export failed');
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
            notifySuccess(`Loaded backup from ${json.exportedAt || 'unknown date'}`);
        } catch {
            notifyError('❌ Invalid backup file — please choose a LumiPOS backup JSON');
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

    const doImport = async () => {
        setConfirmOpen(false);
        setImporting(true);
        try {
            const res = await dataService.importAll(preview, mode);
            const counts = res.data.data.counts;
            const summary = Object.entries(counts).map(([k, v]) => `${k}: ${v}`).join(', ');
            notifySuccess(`✅ Import complete — ${summary}`);
            setPreview(null);
            setFileName('');
        } catch (err) {
            notifyError(err.response?.data?.message || 'Import failed');
        } finally {
            setImporting(false);
        }
    };

    return (
        <Box sx={{ maxWidth: 760, mx: 'auto' }}>
            <Typography variant="h4" gutterBottom sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <StorageIcon color="primary" /> Data Management
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
                Export your business data to a USB drive or file, and restore it anytime —
                even after a reinstall, crash, or moving to a new computer.
            </Typography>

            {/* ================= EXPORT CARD ================= */}
            <Paper elevation={0} sx={{ p: 3, mb: 3, border: '1px solid', borderColor: 'divider', borderRadius: 3 }}>
                <Typography variant="h6" gutterBottom>📤 Export Backup</Typography>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                    Downloads a single JSON file containing all products, categories, customers,
                    suppliers, sales, and purchases. Save it to a USB drive for safekeeping.
                </Typography>
                <Button
                    variant="contained"
                    size="large"
                    startIcon={exporting ? <CircularProgress size={20} sx={{ color: 'white' }} /> : <DownloadIcon />}
                    onClick={handleExport}
                    disabled={exporting}
                >
                    {exporting ? 'Exporting...' : 'Export All Data (JSON)'}
                </Button>
            </Paper>

            {/* ================= IMPORT CARD ================= */}
            <Paper elevation={0} sx={{ p: 3, border: '1px solid', borderColor: 'divider', borderRadius: 3 }}>
                <Typography variant="h6" gutterBottom>📥 Import / Restore Backup</Typography>

                <input
                    type="file"
                    accept=".json,application/json"
                    ref={fileInputRef}
                    style={{ display: 'none' }}
                    onChange={handleFileSelect}
                />
                <Button variant="outlined" startIcon={<UploadIcon />} onClick={() => fileInputRef.current?.click()}>
                    Choose Backup File
                </Button>

                {preview && (
                    <>
                        <Alert severity="info" sx={{ mt: 2 }}>
                            <strong>{fileName}</strong> — exported at {preview.exportedAt || 'unknown'}
                        </Alert>

                        {/* Preview counts */}
                        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mt: 2 }}>
                            {Object.entries(preview.data || {}).map(([key, arr]) => (
                                <Chip key={key} label={`${key}: ${Array.isArray(arr) ? arr.length : 0}`} variant="outlined" />
                            ))}
                        </Box>

                        <Divider sx={{ my: 2 }} />

                        {/* Mode selector */}
                        <Typography variant="subtitle2" sx={{ mb: 1 }}>Import Mode:</Typography>
                        <Stack direction="row" spacing={1}>
                            <Button
                                variant={mode === 'MERGE' ? 'contained' : 'outlined'}
                                color="primary"
                                onClick={() => setMode('MERGE')}
                            >
                                🔀 Merge (Safe)
                            </Button>
                            <Button
                                variant={mode === 'REPLACE_ALL' ? 'contained' : 'outlined'}
                                color="error"
                                onClick={() => setMode('REPLACE_ALL')}
                            >
                                ♻️ Replace Everything
                            </Button>
                        </Stack>
                        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
                            {mode === 'MERGE'
                                ? 'Keeps existing records, adds new ones, updates matching IDs. Best for combining data.'
                                : 'DELETES all current data first, then loads the backup. Use after a fresh install.'}
                        </Typography>

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
                            {importing ? 'Importing...' : mode === 'MERGE' ? 'Start Merge Import' : 'Replace All & Import'}
                        </Button>
                    </>
                )}
            </Paper>

            {/* ============ REPLACE_ALL CONFIRMATION DIALOG ============ */}
            <Dialog open={confirmOpen} onClose={() => setConfirmOpen(false)}>
                <DialogTitle>⚠️ Replace All Data?</DialogTitle>
                <DialogContent>
                    <Alert severity="error" sx={{ mb: 2 }}>
                        This will <strong>permanently delete</strong> ALL current products, sales,
                        customers, and purchases before loading the backup file.
                    </Alert>
                    <Typography variant="body2">
                        Tip: Export your current data first as a safety copy.
                    </Typography>
                    <FormControlLabel
                        sx={{ mt: 2 }}
                        control={
                            <Checkbox
                                checked={confirmChecked}
                                onChange={(e) => setConfirmChecked(e.target.checked)}
                            />
                        }
                        label="I understand this cannot be undone"
                    />
                </DialogContent>
                <DialogActions>
                    <Button onClick={() => setConfirmOpen(false)}>Cancel</Button>
                    <Button variant="contained" color="error" disabled={!confirmChecked} onClick={doImport}>
                        Yes, Replace Everything
                    </Button>
                </DialogActions>
            </Dialog>
        </Box>
    );
};

export default DataManagement;