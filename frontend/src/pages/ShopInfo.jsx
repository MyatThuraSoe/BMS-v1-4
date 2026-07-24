import { useEffect, useMemo, useState } from 'react';
import { Box, Typography, Paper, TextField, MenuItem, Button, Alert, CircularProgress } from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { shopInfoService } from '../api/services';

import { CloudUpload as UploadIcon, Delete as DeleteIcon, Save as SaveIcon } from '@mui/icons-material';
import { useAuth } from '../context/AuthContext';
import ShopLogo, { clearShopLogoCache } from '../components/ShopLogo';
const SHOP_TYPES = ['MINI_MART','GROCERY','PHARMACY','FURNITURE_SHOP','ELECTRONICS','CLOTHING','RESTAURANT','OTHER'];

const ShopInfo = () => {

  
  const [logoPreview, setLogoPreview] = useState(null);
  const [logoRefresh, setLogoRefresh] = useState(0);

  const queryClient = useQueryClient();
  const { isAdmin } = useAuth();

  const { data, isLoading, error } = useQuery({
    queryKey: ['shopInfo'],
    queryFn: () => shopInfoService.get(),
    enabled: isAdmin(),
  });

  const [form, setForm] = useState({
    shopName: '',
    shopType: 'OTHER',
    address: '',
    phone: '',
    email: '',
  });

  useEffect(() => {
    if (!data?.data) return;
    const d = data.data;
    setForm({
      shopName: d.shopName || '',
      shopType: d.shopType || 'OTHER',
      address: d.address || '',
      phone: d.phone || '',
      email: d.email || '',
    });
  }, [data]);

  const [logoFile, setLogoFile] = useState(null);

  const updateMutation = useMutation({
    mutationFn: (payload) => shopInfoService.update(payload),
    onSuccess: () => {
      queryClient.invalidateQueries(['shopInfo']);
    },
  });

  const uploadLogoMutation = useMutation({
      mutationFn: (file) => shopInfoService.uploadLogo(file),

      onSuccess: async () => {

          if (logoPreview) {
              URL.revokeObjectURL(logoPreview);
          }
          clearShopLogoCache();
          setLogoRefresh(v => v + 1);

          setLogoPreview(null);
          setLogoFile(null);

          await queryClient.invalidateQueries({
              queryKey: ['shopInfo'],
          });

          setLogoRefresh(v => v + 1);
          setLogoFile(null);
      }
    });

  const deleteLogoMutation = useMutation({
      mutationFn: () => shopInfoService.deleteLogo(),

      onSuccess: async () => {

        if (logoPreview) {
            URL.revokeObjectURL(logoPreview);
        }
        clearShopLogoCache();
        setLogoRefresh(v => v + 1);
        setLogoPreview(null);
        setLogoFile(null);

        await queryClient.invalidateQueries({
            queryKey: ['shopInfo'],
        });

        setLogoRefresh(v => v + 1);
    }
    });

  const handleSave = () => {
    if (!form.shopName?.trim()) return;
    updateMutation.mutate({
      shopName: form.shopName,
      shopType: form.shopType,
      address: form.address,
      phone: form.phone,
      email: form.email,
    });
  };

  if (!isAdmin()) {
    return <Typography color="text.secondary">Not authorized</Typography>;
  }

  if (isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', mt: 4 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (error) {
    return <Alert severity="error">Failed to load shop info</Alert>;
  }

  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        Shop Information
      </Typography>

      <GridLayout>
        <Paper sx={{ p: 3 }}>
          <Typography variant="h6" gutterBottom>
            Shop Details
          </Typography>

          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <TextField
              required
              label="Shop Name"
              value={form.shopName}
              onChange={(e) => setForm((p) => ({ ...p, shopName: e.target.value }))}
              fullWidth
            />

            <TextField
              select
              label="Shop Type"
              value={form.shopType}
              onChange={(e) => setForm((p) => ({ ...p, shopType: e.target.value }))}
              fullWidth
            >
              {SHOP_TYPES.map((t) => (
                <MenuItem key={t} value={t}>
                  {t}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              label="Address"
              value={form.address}
              onChange={(e) => setForm((p) => ({ ...p, address: e.target.value }))}
              fullWidth
              multiline
              minRows={3}
            />

            <TextField
              label="Phone"
              value={form.phone}
              onChange={(e) => setForm((p) => ({ ...p, phone: e.target.value }))}
              fullWidth
            />

            <TextField
              label="Email"
              value={form.email}
              onChange={(e) => setForm((p) => ({ ...p, email: e.target.value }))}
              fullWidth
            />

            <Box sx={{ display: 'flex', gap: 1, mt: 1 }}>
              <Button
                variant="contained"
                startIcon={<SaveIcon />}
                onClick={handleSave}
                disabled={updateMutation.isPending || !form.shopName?.trim()}
              >
                Save
              </Button>
            </Box>

            {updateMutation.isError && (
              <Alert severity="error">Failed to update shop info</Alert>
            )}
          </Box>
        </Paper>

        <Paper sx={{ p: 3 }}>
          <Typography variant="h6" gutterBottom>
            Logo
          </Typography>

          <Box sx={{ display: 'flex', gap: 2, alignItems: 'center', flexWrap: 'wrap' }}>
            <ShopLogo
                size={110}
                hasLogo={data?.data?.hasLogo}
                preview={logoPreview}
                refreshTrigger={logoRefresh}
            />
            

            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, flex: 1, minWidth: 220 }}>
              <Button
                variant="outlined"
                component="label"
                startIcon={<UploadIcon />}
                disabled={uploadLogoMutation.isPending}
              >
                Upload Logo
                <input
                    hidden
                    type="file"
                    accept="image/*"
                    onChange={(e) => {

                        const file = e.target.files?.[0];

                        setLogoFile(file || null);

                        if (logoPreview) {
                            URL.revokeObjectURL(logoPreview);
                        }

                        if (file) {
                            setLogoPreview(URL.createObjectURL(file));
                        } else {
                            setLogoPreview(null);
                        }

                    }}
                />
              </Button>

              <Button
                variant="contained"
                disabled={!logoFile || uploadLogoMutation.isPending}
                onClick={() => logoFile && uploadLogoMutation.mutate(logoFile)}
              >
                {uploadLogoMutation.isPending ? 'Uploading...' : 'Replace Logo'}
              </Button>

              <Button
                variant="text"
                color="error"
                startIcon={<DeleteIcon />}
                disabled={deleteLogoMutation.isPending}
                onClick={() => deleteLogoMutation.mutate()}
              >
                Delete Logo
              </Button>

              {uploadLogoMutation.isError && (
                <Alert severity="error">Failed to upload logo</Alert>
              )}
              {deleteLogoMutation.isError && (
                <Alert severity="error">Failed to delete logo</Alert>
              )}
            </Box>
          </Box>
        </Paper>
      </GridLayout>
    </Box>
  );
};

const GridLayout = ({ children }) => {
  return <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: 2 }}>{children}</Box>;
};

export default ShopInfo;

