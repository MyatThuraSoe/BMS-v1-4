import { useEffect, useState } from 'react';
import { Box } from '@mui/material';
import { Image as ImageIcon } from '@mui/icons-material';
import { shopInfoService } from '../api/services';


const ShopLogo = ({ preview, refreshTrigger = 0, size = 96 }) => {
    const [logoUrl, setLogoUrl] = useState(preview || cachedLogoUrl);

    useEffect(() => {
        let cancelled = false;
        let objectUrl = null;

        // Local preview while editing
        if (preview) {
            setLogoUrl(preview);
            return;
        }

        // Use cached logo if available
        if (cachedLogoUrl) {
            setLogoUrl(cachedLogoUrl);
            return;
        }

        const load = async () => {
            try {
                const blob = await shopInfoService.getLogo();

                if (cancelled) return;

                objectUrl = URL.createObjectURL(blob);

                cachedLogoUrl = objectUrl;

                setLogoUrl(objectUrl);
            } catch {
                if (!cancelled) {
                    setLogoUrl(null);
                }
            }
        };

        load();

        return () => {
            cancelled = true;
        };
    }, [preview, refreshTrigger]);

    if (!logoUrl) {
        return (
            <Box
                sx={{
                    width: size,
                    height: size,
                    display: 'flex',
                    justifyContent: 'center',
                    alignItems: 'center',
                    bgcolor: 'action.hover',
                    borderRadius: 1,
                    color: 'text.disabled',
                }}
            >
                <ImageIcon sx={{ fontSize: size * 0.45 }} />
            </Box>
        );
    }

    return (
        <Box
            component="img"
            src={logoUrl}
            alt="Shop Logo"
            sx={{
                width: size,
                height: size,
                objectFit: 'contain',
                borderRadius: 1,
            }}
        />
    );
};

export default ShopLogo;

let cachedLogoUrl = null;

export const clearShopLogoCache = () => {
    if (cachedLogoUrl) {
        URL.revokeObjectURL(cachedLogoUrl);
    }
    cachedLogoUrl = null;
};
