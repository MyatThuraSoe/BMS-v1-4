import { useState, useEffect, useRef } from 'react';
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
  Chip,
  CircularProgress,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { auditLogService } from '../api/services';

const AuditLogs = () => {
  const { t } = useTranslation('settings');
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [filters, setFilters] = useState({
    action: '',
    startDate: '',
    endDate: '',
  });
  const [debouncedFilters, setDebouncedFilters] = useState(filters);
  const debounceRef = useRef(null);

  // Debounce filter changes by 500ms so the query only fires after the user
  // stops typing — prevents re-renders that unmount the input and lose focus.
  useEffect(() => {
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      setDebouncedFilters(filters);
    }, 500);
    return () => clearTimeout(debounceRef.current);
  }, [filters]);

  const { data: logsData, isFetching } = useQuery({
    queryKey: ['audit-logs', page, size, debouncedFilters],
    queryFn: () => auditLogService.getAll(page, size, debouncedFilters),
  });

  const logs = logsData?.data?.content || [];
  const totalElements = logsData?.data?.page?.totalElements || 0;
  const totalPages = logsData?.data?.page?.totalPages || 0;

  const handleFilterChange = (e) => {
    const { name, value } = e.target;
    setFilters((prev) => ({ ...prev, [name]: value }));
    setPage(0); // Reset to first page on filter change
  };

  const getActionChipColor = (action) => {
    if (action.includes('CREATE') || action.includes('LOGIN')) return 'success';
    if (action.includes('UPDATE')) return 'warning';
    if (action.includes('DELETE') || action.includes('VOID')) return 'error';
    return 'default';
  };

  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        {t('audit_logs')}
      </Typography>

      <Paper sx={{ p: 2, mb: 2 }}>
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
          <TextField
            size="small"
            label={t('action')}
            name="action"
            value={filters.action}
            onChange={handleFilterChange}
            placeholder={t('action_placeholder')}
            sx={{ minWidth: 200 }}
          />
          <TextField
            size="small"
            label={t('start_date')}
            name="startDate"
            type="date"
            value={filters.startDate}
            onChange={handleFilterChange}
            InputLabelProps={{ shrink: true }}
            sx={{ minWidth: 150 }}
          />
          <TextField
            size="small"
            label={t('end_date')}
            name="endDate"
            type="date"
            value={filters.endDate}
            onChange={handleFilterChange}
            InputLabelProps={{ shrink: true }}
            sx={{ minWidth: 150 }}
          />
          <Button
            variant="outlined"
            onClick={() => setFilters({ action: '', startDate: '', endDate: '' })}
          >
            {t('clear_filters')}
          </Button>
        </Box>
      </Paper>

      <TableContainer component={Paper} sx={{ position: 'relative' }}>
        {isFetching && (
          <Box
            sx={{
              position: 'absolute',
              inset: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              backgroundColor: 'rgba(255,255,255,0.5)',
              zIndex: 1,
            }}
          >
            <CircularProgress size={28} />
          </Box>
        )}
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>{t('col_id')}</TableCell>
              <TableCell>{t('col_timestamp')}</TableCell>
              <TableCell>{t('col_user')}</TableCell>
              <TableCell>{t('action')}</TableCell>
              <TableCell>{t('col_entity')}</TableCell>
              <TableCell>{t('col_description')}</TableCell>
              <TableCell>{t('col_ip')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {logs.map((log) => (
              <TableRow key={log.id}>
                <TableCell>{log.id}</TableCell>
                <TableCell>
                  {new Date(log.timestamp).toLocaleString()}
                </TableCell>
                <TableCell>{log.username || t('user_id_fallback', { id: log.userId })}</TableCell>
                <TableCell>
                  <Chip
                    label={log.action}
                    color={getActionChipColor(log.action)}
                    size="small"
                  />
                </TableCell>
                <TableCell>
                  {log.entityType} #{log.entityId}
                </TableCell>
                <TableCell>
                  <Typography variant="body2" noWrap sx={{ maxWidth: 300 }}>
                    {log.description}
                  </Typography>
                </TableCell>
                <TableCell>{log.ipAddress || '-'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mt: 2 }}>
        <Typography variant="body2" color="text.secondary">
          {t('showing_logs', { shown: logs.length, total: totalElements })}
        </Typography>
        <Box>
          <Button
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
          >
            {t('previous')}
          </Button>
          <Button
            disabled={page >= totalPages - 1}
            onClick={() => setPage(page + 1)}
          >
            {t('next')}
          </Button>
        </Box>
      </Box>
    </Box>
  );
};

export default AuditLogs;
