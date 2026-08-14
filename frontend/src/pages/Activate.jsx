import { useState, useEffect } from 'react';
import { Box, Paper, Typography, TextField, Button, IconButton, InputAdornment, Chip, CircularProgress, Alert } from '@mui/material';
import { ContentCopy as CopyIcon, Verified as VerifiedIcon, FlashOn as FlashIcon } from '@mui/icons-material';
import { licenseService } from '../api/services';
import { notifySuccess, notifyError } from '../utils/notify';


const Activate = () => {
    const [machineId, setMachineId] = useState('');
    const [licenseKey, setLicenseKey] = useState('');
    const [activating, setActivating] = useState(false);
    const [loading, setLoading] = useState(true);

    const [licenseStatus, setLicenseStatus] = useState(null);

    const planLabel = (plan) =>
    plan === 'trial' ? '1-Month Trial' : plan === 'year' ? '1-Year License' : 'Lifetime License';

    useEffect(() => {
        licenseService.getMachineId()
            .then(res => setMachineId(res.data.data.machineId))
            .catch(() => notifyError("Could not read this computer's ID"))
            .finally(() => setLoading(false));

            licenseService.getStatus().then(res => setLicenseStatus(res.data.data)).catch(() =>{});
    }, []);



    const copyMachineId = async () => {
        try {
            await navigator.clipboard.writeText(machineId);
            notifySuccess('Machine ID copied - send it to MegaCode');
        } catch (_err) {
            notifyError('Copy failed - please type it manually');
        }
    };

    const handleActivate = async () => {
        if (!licenseKey.trim()) return notifyError('Please paste your license key first');
        setActivating(true);
        try {
            const res = await licenseService.activate(licenseKey.trim());
            if (res.data.data.activated) {
                notifySuccess('LumiPOS activated on this computer!');
                setTimeout(() => { window.location.href = '/'; }, 1200);
            } else {
                notifyError(res.data.message || 'Invalid license for this machine');
            }
        } catch (err) {
            notifyError(err.friendlyMessage || 'Activation failed');
        } finally {
            setActivating(false);
        }
    };

    return (
        <Box sx={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', bgcolor: 'background.default', p: 2 }}>
            <Paper elevation={0} sx={{ maxWidth: 520, width: '100%', p: { xs: 3, md: 5 }, border: '1px solid', borderColor: 'divider', borderRadius: 3, textAlign: 'center' }}>
                <Box sx={{ width: 64, height: 64, borderRadius: 2, bgcolor: 'primary.main', color: 'white', display: 'flex', alignItems: 'center', justifyContent: 'center', mx: 'auto', mb: 2, fontFamily: '"Fraunces", serif', fontSize: '2rem', fontWeight: 700 }}>
                    L
                </Box>
                <Typography variant="h5" sx={{ fontWeight: 600, mb: 0.5 }}>Activate LumiPOS</Typography>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
                    This copy is locked to this computer. Send the Machine ID below to MegaCode to receive your license key.
                </Typography>

                {loading ? <CircularProgress /> : (
                    <>

                    {licenseStatus?.expired && (
                        <Alert severity="warning" sx={{ mb: 2, textAlign: 'left' }}>
                            Your <strong>{planLabel(licenseStatus.plan)}</strong> ended.
                            Your data is 100% safe — paste a new license key below to unlock again.
                        </Alert>
                    )}
                        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5, textAlign: 'left' }}>
                            1. Your Machine ID
                        </Typography>
                        <TextField
                            fullWidth
                            value={machineId}
                            InputProps={{
                                readOnly: true,
                                sx: { fontFamily: '"IBM Plex Mono", monospace', fontWeight: 700, letterSpacing: 1 },
                                endAdornment: (
                                    <InputAdornment position="end">
                                        <IconButton onClick={copyMachineId}><CopyIcon /></IconButton>
                                    </InputAdornment>
                                ),
                            }}
                            sx={{ mb: 3 }}
                        />

                        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5, textAlign: 'left' }}>
                            2. Paste License Key (from MegaCode)
                        </Typography>
                        <TextField
                            fullWidth
                            multiline
                            minRows={4}
                            placeholder="Paste the long license key you received..."
                            value={licenseKey}
                            onChange={(e) => setLicenseKey(e.target.value)}
                            sx={{ mb: 3, '& textarea': { fontFamily: '"IBM Plex Mono", monospace', fontSize: '0.8rem' } }}
                        />

                        <Button
                            fullWidth
                            size="large"
                            variant="contained"
                            onClick={handleActivate}
                            disabled={activating || !licenseKey.trim()}
                            startIcon={activating ? <CircularProgress size={18} sx={{ color: 'white' }} /> : <VerifiedIcon />}
                            sx={{ py: 1.4 }}
                        >
                            {activating ? 'Activating...' : 'Activate LumiPOS'}
                        </Button>

                        <Box sx={{ mt: 3 }}>
                            <Chip size="small" variant="outlined" icon={<FlashIcon />} label="MegaCode Software Development" />
                            <Typography variant="caption" display="block" color="text.secondary" sx={{ mt: 1 }}>
                                facebook.com/MegaCodemm - LinkedIn: MegaCode Software Development
                            </Typography>
                        </Box>
                    </>
                )}
            </Paper>
        </Box>
    );
};

export default Activate;
