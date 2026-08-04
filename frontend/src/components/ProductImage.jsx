import { useState, useEffect } from 'react';
import { Box } from '@mui/material';
import { Inventory as PlaceholderIcon } from '@mui/icons-material';
import { productService } from '../api/services';

const ProductImage = ({ productId, hasImage, size = 60 }) => {
  const [imageUrl, setImageUrl] = useState(null);

  useEffect(() => {
    let objectUrl;
    let cancelled = false;

    if (hasImage && productId) {
      productService.getImage(productId).then((blob) => {
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
  }, [productId, hasImage]);

  if (!hasImage || !imageUrl) {
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
      alt="Product"
      sx={{ width: size, height: size, objectFit: 'cover', borderRadius: 1 }}
    />
  );
};

export default ProductImage;