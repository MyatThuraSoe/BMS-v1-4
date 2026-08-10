import { Box, Typography, Button } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

const NotFound = () => {
  const navigate = useNavigate();
  const { defaultRoute } = useAuth();
  const { t } = useTranslation('auth');

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: '80vh', gap: 3 }}>
      <Typography variant="h1" color="primary">404</Typography>
      <Typography variant="h5" color="text.secondary">{t('page_not_found')}</Typography>
      <Typography variant="body2" color="text.secondary">
        {t('page_not_found_desc')}
      </Typography>
      <Button variant="contained" onClick={() => navigate(defaultRoute)}>
        {t('back_to_home')}
      </Button>
    </Box>
  );
};

export default NotFound;
