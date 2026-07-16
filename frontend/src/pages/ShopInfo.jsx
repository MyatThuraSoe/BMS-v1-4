import { useState, useEffect } from 'react';
import {
  Box,
  Card,
  CardContent,
  Typography,
  TextField,
  Button,
  Grid,
  FormControl,
  InputLabel,
  Select,
  MenuItem,
  Alert,
  CircularProgress,
} from '@mui/material';
import { Save as SaveIcon, Delete as DeleteIcon, AddPhotoAlternate as UploadIcon } from '@mui/icons-material';
import { shopInfoService } from '../api/services';
import ShopLogo from '../components/ShopLogo';

const SHOP_TYPES = [
  'MINI_MART',
  'GROCERY',
  'PHARMACY',
  'FURNITURE_SHOP',
  'ELECTRONICS',
  'CLOTHING',
  'RESTAURANT',
  'OTHER',
];

const ShopInfo = () => {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);
  const [hasLogo, setHasLogo] = useState(false);
  const [formData, setFormData] = useState({
    shopName: '',
    shopType: 'OTHER',
    address: '',
    phone: '',
    email: '',
  });

  useEffect(() => {
    loadShopInfo();
  }, []);

  const loadShopInfo = async () => {
    try {
      setLoading(true);
      const response = await shopInfoService.getShopInfo();
      if (response.success && response.data) {
        const data = response.data;
        setFormData({
          shopName: data.shopName || '',
          shopType: data.shopType || 'OTHER',
          address: data.address || '',
          phone: data.phone || '',
          email: data.email || '',
        });
        setHasLogo(data.hasLogo || false);
      }
    } catch (err) {
      setError('Failed to load shop info');
    } finally {
      setLoading(false);
    }
  };

  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    try {
      setSaving(true);
      setError(null);
      setSuccess(null);
      
      const response = await shopInfoService.updateShopInfo(formData);
      if (response.success) {
        setSuccess('Shop info updated successfully');
      } else {
        setError(response.message || 'Failed to update shop info');
      }
    } catch (err) {
      setError('Failed to update shop info');
    } finally {
      setSaving(false);
    }
  };

  const handleLogoUpload = async (e) => {
    const file = e.target.files[0];
    if (!file) return;

    try {
      setSaving(true);
      setError(null);
      setSuccess(null);

      const response = await shopInfoService.uploadLogo(file);
      if (response.success) {
        setHasLogo(true);
        setSuccess('Logo uploaded successfully');
      } else {
        setError(response.message || 'Failed to upload logo');
      }
    } catch (err) {
      setError('Failed to upload logo');
    } finally {
      setSaving(false);
    }
  };

  const handleDeleteLogo = async () => {
    try {
      setSaving(true);
      setError(null);
      setSuccess(null);

      const response = await shopInfoService.deleteLogo();
      if (response.success) {
        setHasLogo(false);
        setSuccess('Logo deleted successfully');
      } else {
        setError(response.message || 'Failed to delete logo');
      }
    } catch (err) {
      setError('Failed to delete logo');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '400px' }}>
        <CircularProgress />
      </Box>
    );
  }

  return (
    <Box>
      <Typography variant="h5" gutterBottom>
        Shop Information
      </Typography>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {success && (
        <Alert severity="success" sx={{ mb: 2 }} onClose={() => setSuccess(null)}>
          {success}
        </Alert>
      )}

      <Grid container spacing={3}>
        <Grid item xs={12} md={8}>
          <Card>
            <CardContent>
              <form onSubmit={handleSubmit}>
                <Grid container spacing={2}>
                  <Grid item xs={12}>
                    <TextField
                      fullWidth
                      label="Shop Name"
                      name="shopName"
                      value={formData.shopName}
                      onChange={handleChange}
                      required
                    />
                  </Grid>

                  <Grid item xs={12} sm={6}>
                    <FormControl fullWidth required>
                      <InputLabel>Shop Type</InputLabel>
                      <Select
                        name="shopType"
                        value={formData.shopType}
                        label="Shop Type"
                        onChange={handleChange}
                      >
                        {SHOP_TYPES.map((type) => (
                          <MenuItem key={type} value={type}>
                            {type.replace(/_/g, ' ')}
                          </MenuItem>
                        ))}
                      </Select>
                    </FormControl>
                  </Grid>

                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      label="Phone"
                      name="phone"
                      value={formData.phone}
                      onChange={handleChange}
                    />
                  </Grid>

                  <Grid item xs={12}>
                    <TextField
                      fullWidth
                      label="Email"
                      name="email"
                      type="email"
                      value={formData.email}
                      onChange={handleChange}
                    />
                  </Grid>

                  <Grid item xs={12}>
                    <TextField
                      fullWidth
                      label="Address"
                      name="address"
                      multiline
                      rows={3}
                      value={formData.address}
                      onChange={handleChange}
                    />
                  </Grid>

                  <Grid item xs={12}>
                    <Button
                      type="submit"
                      variant="contained"
                      color="primary"
                      startIcon={<SaveIcon />}
                      disabled={saving}
                    >
                      {saving ? 'Saving...' : 'Save Shop Info'}
                    </Button>
                  </Grid>
                </Grid>
              </form>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={4}>
          <Card>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Shop Logo
              </Typography>

              <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2 }}>
                <ShopLogo hasLogo={hasLogo} size={150} />

                <input
                  accept="image/*"
                  style={{ display: 'none' }}
                  id="logo-upload"
                  type="file"
                  onChange={handleLogoUpload}
                />
                <label htmlFor="logo-upload">
                  <Button
                    variant="outlined"
                    component="span"
                    startIcon={<UploadIcon />}
                    disabled={saving}
                  >
                    Upload Logo
                  </Button>
                </label>

                {hasLogo && (
                  <Button
                    variant="outlined"
                    color="error"
                    startIcon={<DeleteIcon />}
                    onClick={handleDeleteLogo}
                    disabled={saving}
                  >
                    Delete Logo
                  </Button>
                )}
              </Box>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
};

export default ShopInfo;
