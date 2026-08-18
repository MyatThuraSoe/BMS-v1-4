import { useState, useEffect } from 'react';
import { Box, Paper, Typography, TextField, Button, IconButton, InputAdornment, Chip, CircularProgress, Alert } from '@mui/material';
import { ContentCopy as CopyIcon, Verified as VerifiedIcon, FlashOn as FlashIcon } from '@mui/icons-material';
import { useTranslation } from 'react-i18next';
import { licenseService } from '../api/services';
import { notifySuccess, notifyError } from '../utils/notify';


const Activate = () => {
    const { t } = useTranslation('auth');
    const [machineId, setMachineId] = useState('');
    const [licenseKey, setLicenseKey] = useState('');
    const [activating, setActivating] = useState(false);
    const [loading, setLoading] = useState(true);

    const [licenseStatus, setLicenseStatus] = useState(null);

    const planLabel = (plan) =>
    plan === 'trial' ? t('trial_plan') : plan === 'year' ? t('year_plan') : t('lifetime_plan');

    useEffect(() => {
        licenseService.getMachineId()
            .then(res => setMachineId(res.data.data.machineId))
            .catch(() => notifyError(t('could_not_read_id')))
            .finally(() => setLoading(false));

            licenseService.getStatus().then(res => setLicenseStatus(res.data.data)).catch(() =>{});
    }, [t]);



    const copyMachineId = async () => {
        try {
            await navigator.clipboard.writeText(machineId);
            notifySuccess(t('machine_id_copied'));
        } catch (_err) {
            notifyError(t('copy_failed'));
        }
    };

    const handleActivate = async () => {
        if (!licenseKey.trim()) return notifyError(t('key_required'));
        setActivating(true);
        try {
            const res = await licenseService.activate(licenseKey.trim());
            if (res.data.data.activated) {
                notifySuccess(t('activated_success'));
                setTimeout(() => { window.location.href = '/'; }, 1200);
            } else {
                notifyError(res.data.message || t('invalid_machine'));
            }
        } catch (err) {
            notifyError(err.friendlyMessage || t('activation_failed'));
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
                <Typography variant="h5" sx={{ fontWeight: 600, mb: 0.5 }}>{t('activate_title')}</Typography>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
                    {t('locked_desc')}
                </Typography>

                {loading ? <CircularProgress /> : (
                    <>

                    {licenseStatus?.expired && (
                        <Alert severity="warning" sx={{ mb: 2, textAlign: 'left' }}>
                            {t('expired_warning', { plan: planLabel(licenseStatus.plan) })}
                        </Alert>
                    )}
                        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5, textAlign: 'left' }}>
                            {t('machine_id')}
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
                            {t('paste_key')}
                        </Typography>
                        <TextField
                            fullWidth
                            multiline
                            minRows={4}
                            placeholder={t('paste_key_placeholder')}
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
                            {activating ? t('activating') : t('activate_title')}
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
