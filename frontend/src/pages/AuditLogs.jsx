import { useState } from 'react';
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
  Alert,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { auditLogService } from '../api/services';

const AuditLogs = () => {
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [filters, setFilters] = useState({
    action: '',
    startDate: '',
    endDate: '',
  });

  const { data: logsData, isLoading } = useQuery({
    queryKey: ['audit-logs', page, size, filters],
    queryFn: () => auditLogService.getAll(page, size, filters),
  });

  const logs = logsData?.data?.content || [];
  const totalElements = logsData?.data?.totalElements || 0;
  const totalPages = logsData?.data?.totalPages || 0;

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

  if (isLoading) {
    return <Typography>Loading...</Typography>;
  }

  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        Audit Logs
      </Typography>

      <Paper sx={{ p: 2, mb: 2 }}>
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
          <TextField
            size="small"
            label="Action"
            name="action"
            value={filters.action}
            onChange={handleFilterChange}
            placeholder="e.g., USER_CREATE"
            sx={{ minWidth: 200 }}
          />
          <TextField
            size="small"
            label="Start Date"
            name="startDate"
            type="date"
            value={filters.startDate}
            onChange={handleFilterChange}
            InputLabelProps={{ shrink: true }}
            sx={{ minWidth: 150 }}
          />
          <TextField
            size="small"
            label="End Date"
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
            Clear Filters
          </Button>
        </Box>
      </Paper>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>ID</TableCell>
              <TableCell>Timestamp</TableCell>
              <TableCell>User</TableCell>
              <TableCell>Action</TableCell>
              <TableCell>Entity</TableCell>
              <TableCell>Description</TableCell>
              <TableCell>IP Address</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {logs.map((log) => (
              <TableRow key={log.id}>
                <TableCell>{log.id}</TableCell>
                <TableCell>
                  {new Date(log.timestamp).toLocaleString()}
                </TableCell>
                <TableCell>{log.username || `ID: ${log.userId}`}</TableCell>
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
          Showing {logs.length} of {totalElements} logs
        </Typography>
        <Box>
          <Button
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
          >
            Previous
          </Button>
          <Button
            disabled={page >= totalPages - 1}
            onClick={() => setPage(page + 1)}
          >
            Next
          </Button>
        </Box>
      </Box>
    </Box>
  );
};

export default AuditLogs;
