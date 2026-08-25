import { useState, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { 
  Box, Typography, Paper, Button, Chip, Divider, Stack, Link as MuiLink,
  Dialog, DialogTitle, DialogContent, DialogActions, TextField 
} from '@mui/material';
import { 
  Info as InfoIcon, 
  Facebook as FacebookIcon, 
  LinkedIn as LinkedInIcon, 
  Update as UpdateIcon,
  Telegram as TelegramIcon
} from '@mui/icons-material';

import { licenseService } from '../api/services';
import { notifySuccess, notifyError } from '../utils/notify';
import { useAuth } from '../context/AuthContext';
import QRCode from 'qrcode';

// TikTok SVG Icon - MUI v5 compatible
const TikTokIcon = (props) => (
  <svg
    viewBox="0 0 24 24"
    fill="currentColor"
    width="1em"
    height="1em"
    xmlns="http://www.w3.org/2000/svg"
    {...props}
  >
    <path d="M12.525.02c1.31-.02 2.61-.01 3.91-.02.08 1.53.63 3.09 1.75 4.17 1.12 1.11 2.7 1.62 4.24 1.79v4.03c-1.44-.05-2.89-.35-4.2-.97-.57-.26-1.1-.59-1.62-.93v6.16c0 2.52-1.12 4.84-2.97 6.39-1.48 1.26-3.42 2.03-5.36 2.03-4.14 0-7.5-3.36-7.5-7.5 0-2.97 1.74-5.64 4.38-6.88v4.18c-.87.46-1.52 1.29-1.72 2.28-.34 1.61.51 3.27 2.02 3.93 1.49.66 3.27.13 4.18-1.24.42-.63.58-1.37.58-2.12V.02z" />
  </svg>
);



const About = () => {
  const { t } = useTranslation('about');
  const { isAdmin } = useAuth();
  const appVersion = '1.0.0';
  const buildDate = 'August 2025';

  // QR of the LAN address reported by the backend. On the desktop the page
  // itself loads from 127.0.0.1 (useless to scan), so we ask the server which
  // real Wi-Fi/LAN address it is reachable on; phones already see the right
  // address via location.origin, which stays the fallback.
  const [phoneQr, setPhoneQr] = useState(null);
  const [phoneUrl, setPhoneUrl] = useState('');
  useEffect(() => {
    let cancelled = false;
    const buildQr = async (url) => {
      if (!url) return;
      try {
        const dataUrl = await QRCode.toDataURL(url, { width: 320, margin: 2, color: { dark: '#1C2620', light: '#ffffff' } });
        if (!cancelled) {
          setPhoneUrl(url);
          setPhoneQr(dataUrl);
        }
      } catch { /* ignore */ }
    };
    fetch('/api/public/network-info')
      .then((r) => r.json())
      .then((res) => {
        // Controller returns { urls } unwrapped (no ApiResponse envelope)
        const urls = res?.urls || res?.data?.urls || [];
        buildQr(urls[0] || window.location.origin);
      })
      .catch(() => buildQr(window.location.origin));
    return () => { cancelled = true; };
  }, []);

  const { data: statusData, refetch } = useQuery({
    queryKey: ['license-status'],
    queryFn: () => licenseService.getStatus(),
    enabled: isAdmin(),
});
const lic = statusData?.data?.data;
const [keyDialog, setKeyDialog] = useState(false);
const [newKey, setNewKey] = useState('');

const handleActivateNewKey = async () => {
    const res = await licenseService.activate(newKey.trim());
    if (res.data.data.activated) {
        notifySuccess(t('license_updated'));
        setKeyDialog(false);
        setNewKey('');
        refetch();
    } else {
        notifyError(res.data.message || t('invalid_key'));
    }
};

  return (
    <Box sx={{ maxWidth: 800, mx: 'auto', p: { xs: 2, md: 4 } }}>
      {/* Header Card */}
      <Paper
        elevation={0}
        sx={{
          p: { xs: 3, md: 5 },
          mb: 3,
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 3,
          textAlign: 'center',
          background: 'linear-gradient(135deg, rgba(43, 110, 79, 0.03) 0%, rgba(184, 134, 46, 0.03) 100%)',
        }}
      >
        <Box
          component="img"
          src="/LumiPOS-logo.png"
          alt="LumiPOS logo"
          sx={{
            width: 80,
            height: 80,
            mx: 'auto',
            mb: 3,
            display: 'block',
            borderRadius: 3,
            boxShadow: '0 4px 20px rgba(43, 110, 79, 0.3)',
          }}
        />

        <Typography
          variant="h3"
          component="h1"
          sx={{
            fontWeight: 600,
            mb: 1,
            background: 'linear-gradient(135deg, #2B6E4F 0%, #1F5239 100%)',
            backgroundClip: 'text',
            WebkitBackgroundClip: 'text',
            WebkitTextFillColor: 'transparent',
          }}
        >
          LumiPOS
        </Typography>

        <Typography variant="subtitle1" color="text.secondary" sx={{ mb: 2 }}>
          {t('tagline')}
        </Typography>

        <Stack direction="row" spacing={1} justifyContent="center" flexWrap="wrap" useFlexGap>
          <Chip
            icon={<UpdateIcon />}
            label={t('version_chip', { version: appVersion })}
            color="primary"
            variant="outlined"
            sx={{ fontWeight: 600 }}
          />
          <Chip
            label={t('built_chip', { date: buildDate })}
            variant="outlined"
            sx={{ color: 'text.secondary' }}
          />
        </Stack>
      </Paper>

      {/* Use on Phone / Tablet — QR card */}
      {phoneQr && (
        <Paper elevation={0} sx={{ p: 3, mb: 3, border: '1px solid', borderColor: 'divider', borderRadius: 3 }}>
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={3}
            alignItems="center"
            justifyContent="center"
          >
            <Box
              component="img"
              src={phoneQr}
              alt={t('phone_access_qr_alt')}
              sx={{ width: 160, height: 160, borderRadius: 2, border: '1px solid', borderColor: 'divider' }}
            />
            <Box sx={{ textAlign: { xs: 'center', sm: 'left' } }}>
              <Typography variant="h6" sx={{ fontWeight: 600, mb: 0.5 }}>
                📱 {t('phone_access_title')}
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
                {t('phone_access_desc')}
              </Typography>
              <Chip label={phoneUrl} color="primary" variant="outlined" sx={{ fontWeight: 700 }} />
            </Box>
          </Stack>
        </Paper>
      )}

      {isAdmin() && lic && (
    <Paper elevation={0} sx={{ p: 3, mb: 3, border: '1px solid', borderColor: 'divider', borderRadius: 3 }}>
        <Stack direction="row" justifyContent="space-between" alignItems="center" flexWrap="wrap" gap={1}>
            <Box>
                <Typography variant="h6" sx={{ fontWeight: 600 }}>
                    🎫 {lic.plan === 'trial' ? t('trial_plan') : lic.plan === 'year' ? t('year_plan') : t('lifetime_plan')}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                    {t('licensed_to', { name: lic.customer || '—' })}
                </Typography>
            </Box>
            <Box sx={{ textAlign: 'right' }}>
                <Chip
                  color={
                      lic.plan === 'lifetime' ? 'success' : 
                      lic.expired ? 'error' :
                      lic.plan === 'year' ? 'primary' : 
                      'warning'
                  }
                  label={
                      lic.plan === 'lifetime' ? t('lifetime_badge') : 
                      lic.expired ? t('expired_badge') :
                      lic.daysLeft <= 7 ? t('days_left_warning', { count: lic.daysLeft }) :
                      t('days_left', { count: lic.daysLeft })
                  }
              />
                <Box>
                    <Button size="small" sx={{ mt: 1 }} onClick={() => setKeyDialog(true)}>
                        {t('enter_new_key')}
                    </Button>
                </Box>
            </Box>
        </Stack>

        <Dialog open={keyDialog} onClose={() => setKeyDialog(false)} fullWidth maxWidth="sm">
            <DialogTitle>{t('upgrade_renew')}</DialogTitle>
            <DialogContent>
                <TextField
                    autoFocus fullWidth multiline minRows={4} sx={{ mt: 1 }}
                    placeholder={t('paste_key_placeholder')}
                    value={newKey} onChange={(e) => setNewKey(e.target.value)}
                />
            </DialogContent>
            <DialogActions>
                <Button onClick={() => setKeyDialog(false)}>{t('cancel')}</Button>
                <Button variant="contained" onClick={handleActivateNewKey} disabled={!newKey.trim()}>
                    {t('activate')}
                </Button>
            </DialogActions>
        </Dialog>
    </Paper>
)}

      {/* Company Info Card */}
      <Paper
        elevation={0}
        sx={{
          p: { xs: 3, md: 4 },
          mb: 3,
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 3,
        }}
      >
        <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mb: 3 }}>
          <Box
            component="img"
            src="/MegaCode-Logo.png"
            alt="MegaCode logo"
            sx={{ width: 40, height: 40, borderRadius: 1, objectFit: 'contain' }}
          />
          <Box>
            <Typography variant="h5" sx={{ fontWeight: 600 }}>
              {t('developed_by')}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {t('dev_company')}
            </Typography>
          </Box>
        </Stack>

        <Typography variant="body1" sx={{ mb: 3, lineHeight: 1.8, color: 'text.primary' }}>
          {t('intro_1')}
        </Typography>

        <Typography variant="body1" sx={{ lineHeight: 1.8, color: 'text.primary' }}>
          {t('intro_2')}
        </Typography>

        <Divider sx={{ my: 3 }} />

        <Typography variant="h6" sx={{ fontWeight: 600, mb: 2 }}>
          {t('connect')}
        </Typography>

        <Stack spacing={2}>
          <Button
            component={MuiLink}
            href="https://www.facebook.com/MegaCodemm"
            target="_blank"
            rel="noopener noreferrer"
            variant="outlined"
            size="large"
            startIcon={<FacebookIcon />}
            sx={{
              justifyContent: 'flex-start',
              py: 1.5,
              px: 3,
              borderColor: '#1877F2',
              color: '#1877F2',
              '&:hover': {
                borderColor: '#1877F2',
                bgcolor: 'rgba(24, 119, 242, 0.04)',
              },
            }}
          >
            <Typography variant="body1" sx={{ fontWeight: 500, ml: 1 }}>
              facebook.com/MegaCodemm
            </Typography>
          </Button>

          <Button
            component={MuiLink}
            href="https://www.tiktok.com/@megacodemm"
            target="_blank"
            rel="noopener noreferrer"
            variant="outlined"
            size="large"
            startIcon={<TikTokIcon />}
            sx={{
              justifyContent: 'flex-start',
              py: 1.5,
              px: 3,
              borderColor: '#000000',
              color: '#000000',
              '&:hover': {
                borderColor: '#000000',
                bgcolor: 'rgba(0, 0, 0, 0.04)',
              },
            }}
          >
            <Typography variant="body1" sx={{ fontWeight: 500, ml: 1 }}>
              tiktok.com/@megacodemm
            </Typography>
          </Button>

          <Button
            component={MuiLink}
            href="https://t.me/megacodemm"
            target="_blank"
            rel="noopener noreferrer"
            variant="outlined"
            size="large"
            startIcon={<TelegramIcon />}
            sx={{
              justifyContent: 'flex-start',
              py: 1.5,
              px: 3,
              borderColor: '#229ED9',
              color: '#229ED9',
              '&:hover': {
                borderColor: '#229ED9',
                bgcolor: 'rgba(34, 158, 217, 0.04)',
              },
            }}
          >
            <Typography variant="body1" sx={{ fontWeight: 500, ml: 1 }}>
              t.me/megacodemm
            </Typography>
          </Button>

          <Button
            component={MuiLink}
            href="https://www.linkedin.com/company/megacode-software-development"
            target="_blank"
            rel="noopener noreferrer"
            variant="outlined"
            size="large"
            startIcon={<LinkedInIcon />}
            sx={{
              justifyContent: 'flex-start',
              py: 1.5,
              px: 3,
              borderColor: '#0A66C2',
              color: '#0A66C2',
              '&:hover': {
                borderColor: '#0A66C2',
                bgcolor: 'rgba(10, 102, 194, 0.04)',
              },
            }}
          >
            <Typography variant="body1" sx={{ fontWeight: 500, ml: 1 }}>
              LinkedIn / MegaCode Software Development
            </Typography>
          </Button>
        </Stack>
      </Paper>

      {/* Features Card */}
      <Paper
        elevation={0}
        sx={{
          p: { xs: 3, md: 4 },
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 3,
          bgcolor: 'background.default',
        }}
      >
        <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mb: 2 }}>
          <InfoIcon color="primary" />
          <Typography variant="h6" sx={{ fontWeight: 600 }}>
            {t('what_we_build')}
          </Typography>
        </Stack>

        <Stack spacing={1.5}>
          {[
            'feat_pos',
            'feat_inventory',
            'feat_erp',
            'feat_offline',
            'feat_mobile',
            'feat_web',
            'feat_db',
            'feat_network',
          ].map((feature, idx) => (
            <Stack key={idx} direction="row" spacing={1.5} alignItems="center">
              <Box
                sx={{
                  width: 6,
                  height: 6,
                  borderRadius: '50%',
                  bgcolor: 'primary.main',
                  flexShrink: 0,
                }}
              />
              <Typography variant="body2" color="text.secondary">
                {t(feature)}
              </Typography>
            </Stack>
          ))}
        </Stack>

        <Divider sx={{ my: 3 }} />

        <Typography
          variant="body2"
          color="text.secondary"
          textAlign="center"
          sx={{ fontStyle: 'italic' }}
        >
          {t('cta')}
        </Typography>
      </Paper>

      {/* Footer */}
      <Box sx={{ mt: 4, textAlign: 'center' }}>
        <Typography variant="caption" color="text.secondary">
          {t('copyright', { year: new Date().getFullYear() })}
        </Typography>
        <Typography variant="caption" display="block" color="text.secondary" sx={{ mt: 0.5 }}>
          {t('made_in')}
        </Typography>
      </Box>
    </Box>
  );
};

export default About;