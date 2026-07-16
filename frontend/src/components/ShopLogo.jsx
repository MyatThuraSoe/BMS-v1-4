import { useState, useEffect } from 'react';
import { Box } from '@mui/material';
import { Store as PlaceholderIcon } from '@mui/icons-material';
import { shopInfoService } from '../api/services';

const ShopLogo = ({ hasLogo, size = 60 }) => {
  const [imageUrl, setImageUrl] = useState(null);

  useEffect(() => {
    let objectUrl;
    let cancelled = false;

    if (hasLogo) {
      shopInfoService.getLogo().then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setImageUrl(objectUrl);
      }).catch(() => {
        if (!cancelled) setImageUrl(null);
      });
    }

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [hasLogo]);

  if (!hasLogo || !imageUrl) {
    return (
      <Box
        sx={{
          width: size, height: size, display: 'flex', alignItems: 'center', justifyContent: 'center',
          bgcolor: 'action.hover', borderRadius: 1, color: 'text.disabled',
        }}
      >
        <PlaceholderIcon sx={{ fontSize: size * 0.5 }} />
      </Box>
    );
  }

  return (
    <Box
      component="img"
      src={imageUrl}
      alt="Shop Logo"
      sx={{ width: size, height: size, objectFit: 'contain', borderRadius: 1 }}
    />
  );
};

export default ShopLogo;
