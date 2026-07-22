import { Box, Typography, Button } from '@mui/material';
import { useNavigate } from 'react-router-dom';

const NotFound = () => {
  const navigate = useNavigate();

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: '80vh', gap: 3 }}>
      <Typography variant="h1" color="primary">404</Typography>
      <Typography variant="h5" color="text.secondary">Page Not Found</Typography>
      <Typography variant="body2" color="text.secondary">
        The page you're looking for doesn't exist or has been moved.
      </Typography>
      <Button variant="contained" onClick={() => navigate('/dashboard')}>
        Back to Dashboard
      </Button>
    </Box>
  );
};

export default NotFound;
