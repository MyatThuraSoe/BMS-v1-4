import { useState, useEffect } from 'react';
import {
  Box,
  Typography,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  TextField,
  Button,
  Grid,
  Alert,
  Switch,
  FormControlLabel,
} from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { systemSettingService } from '../api/services';

const Settings = () => {
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const queryClient = useQueryClient();

  const { data: settingsData, isLoading } = useQuery({
    queryKey: ['settings'],
    queryFn: () => systemSettingService.getAll(),
  });

  const [settings, setSettings] = useState({});

  useEffect(() => {
    if (settingsData?.data) {
      const settingsMap = {};
      settingsData.data.forEach((setting) => {
        settingsMap[setting.settingKey] = setting;
      });
      setSettings(settingsMap);
    }
  }, [settingsData]);

  const updateMutation = useMutation({
    mutationFn: ({ key, value }) => systemSettingService.update(key, { settingValue: value }),
    onSuccess: () => {
      queryClient.invalidateQueries(['settings']);
      setSuccess('Settings updated successfully');
      setTimeout(() => setSuccess(''), 3000);
    },
    onError: (err) => {
      setError(err.response?.data?.message || 'Failed to update setting');
    },
  });

  const handleSettingChange = (key, value) => {
    setSettings((prev) => ({
      ...prev,
      [key]: { ...prev[key], settingValue: value },
    }));
  };

  const handleSave = (key) => {
    const setting = settings[key];
    if (setting) {
      updateMutation.mutate({ key, value: setting.settingValue });
    }
  };

  const getSettingValue = (key) => {
    return settings[key]?.settingValue || '';
  };

  const renderSettingField = (setting) => {
    const { settingKey, settingValue, dataType, description } = setting;

    switch (dataType) {
      case 'BOOLEAN':
        return (
          <FormControlLabel
            control={
              <Switch
                checked={settingValue === 'true'}
                onChange={(e) => handleSettingChange(settingKey, e.target.checked ? 'true' : 'false')}
              />
            }
            label={settingValue === 'true' ? 'Enabled' : 'Disabled'}
          />
        );
      case 'INTEGER':
        return (
          <TextField
            type="number"
            value={settingValue}
            onChange={(e) => handleSettingChange(settingKey, e.target.value)}
            size="small"
            fullWidth
          />
        );
      case 'DECIMAL':
        return (
          <TextField
            type="number"
            value={settingValue}
            onChange={(e) => handleSettingChange(settingKey, e.target.value)}
            size="small"
            fullWidth
            inputProps={{ step: '0.01' }}
          />
        );
      default:
        return (
          <TextField
            value={settingValue}
            onChange={(e) => handleSettingChange(settingKey, e.target.value)}
            size="small"
            fullWidth
          />
        );
    }
  };

  if (isLoading) {
    return <Typography>Loading...</Typography>;
  }

  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        System Settings
      </Typography>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError('')}>
          {error}
        </Alert>
      )}

      {success && (
        <Alert severity="success" sx={{ mb: 2 }} onClose={() => setSuccess('')}>
          {success}
        </Alert>
      )}

      <Grid container spacing={3}>
        {Object.values(settings).map((setting) => (
          <Grid item xs={12} md={6} key={setting.id}>
            <Paper sx={{ p: 2 }}>
              <Typography variant="subtitle1" gutterBottom>
                {setting.description || setting.settingKey}
              </Typography>
              <Box sx={{ mb: 2 }}>
                {renderSettingField(setting)}
              </Box>
              <Button
                variant="contained"
                size="small"
                onClick={() => handleSave(setting.settingKey)}
                disabled={updateMutation.isPending}
              >
                Save
              </Button>
            </Paper>
          </Grid>
        ))}
      </Grid>
    </Box>
  );
};

export default Settings;
