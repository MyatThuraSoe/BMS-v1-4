import { useState, useEffect, useRef } from 'react';
import { Box } from '@mui/material';
import { Inventory as PlaceholderIcon } from '@mui/icons-material';
import { productService } from '../api/services';

const ProductImage = ({ productId, hasImage, size = 60 }) => {
  const [imageUrl, setImageUrl] = useState(null);
  const [isVisible, setIsVisible] = useState(false);
  const imgRef = useRef(null);

  useEffect(() => {
    if (!imgRef.current || !hasImage || !productId) {
      setIsVisible(false);
      return;
    }

    if (!('IntersectionObserver' in window)) {
      setIsVisible(true);
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            setIsVisible(true);
            observer.disconnect();
          }
        });
      },
      { rootMargin: '200px' }
    );

    observer.observe(imgRef.current);
    return () => observer.disconnect();
  }, [productId, hasImage]);

  useEffect(() => {
    let objectUrl;
    let cancelled = false;

    if (hasImage && productId && isVisible) {
      productService.getImage(productId)
        .then((blob) => {
          if (cancelled) return;
          objectUrl = URL.createObjectURL(blob);
          setImageUrl(objectUrl);
        })
        .catch(() => {
          if (!cancelled) setImageUrl(null);
        });
    } else {
      setImageUrl(null);
    }

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [productId, hasImage, isVisible]);

  const placeholder = (
    <Box
      sx={{
        width: size, height: size, display: 'flex', alignItems: 'center', justifyContent: 'center',
        bgcolor: 'action.hover', borderRadius: 1, color: 'text.disabled',
      }}
    >
      <PlaceholderIcon sx={{ fontSize: size * 0.5 }} />
    </Box>
  );

  if (!hasImage) {
    return placeholder;
  }

  if (!imageUrl) {
    return <Box ref={imgRef}>{placeholder}</Box>;
  }

  return (
    <Box ref={imgRef} sx={{ width: size, height: size, borderRadius: 1, overflow: 'hidden' }}>
      <Box
        component="img"
        src={imageUrl}
        alt="Product"
        loading="lazy"
        decoding="async"
        sx={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
      />
    </Box>
  );
};

export default ProductImage;