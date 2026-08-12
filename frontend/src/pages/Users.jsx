import { useState } from 'react';
import { useTranslation } from 'react-i18next';
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
  IconButton,
  Button,
  Chip,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
} from '@mui/material';
import { Edit as EditIcon, Delete as DeleteIcon, PersonAdd as PersonAddIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { userService } from '../api/services';
import { useAuth } from '../context/AuthContext';
import { useNavigate } from 'react-router-dom';

const Users = () => {
  const [page, setPage] = useState(0);
  const [size] = useState(10);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [selectedUser, setSelectedUser] = useState(null);
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { t } = useTranslation('users');
  const { user: currentUser } = useAuth();

  const { data: usersData, isLoading } = useQuery({
    queryKey: ['users', page, size],
    queryFn: () => userService.getAll(page, size),
  });

  const users = usersData?.data || [];
  const totalElements = users.length;
  const totalPages = 1;

  const deleteMutation = useMutation({
    mutationFn: (id) => userService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] });
      setDeleteDialogOpen(false);
    },
    onError: () => {
      setDeleteDialogOpen(false);
    },
  });

  const handleDelete = (user) => {
    setSelectedUser(user);
    setDeleteDialogOpen(true);
  };

  const confirmDelete = () => {
    if (selectedUser) {
      deleteMutation.mutate(selectedUser.id);
    }
  };

  const roleKeyMap = {
    ROLE_ADMIN: 'role_admin',
    ROLE_MANAGER: 'role_manager',
    ROLE_CASHIER: 'role_cashier',
  };

  const getRoleChip = (roleName) => {
    const roleColors = {
      ROLE_ADMIN: 'error',
      ROLE_MANAGER: 'warning',
      ROLE_CASHIER: 'info',
    };
    return (
      <Chip
        label={roleName ? t(roleKeyMap[roleName] || roleName) : undefined}
        color={roleColors[roleName] || 'default'}
        size="small"
      />
    );
  };

  if (isLoading) {
    return <Typography>{t('loading')}</Typography>;
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3, flexWrap: 'wrap', gap: 2 }}>
        <Typography variant="h4">{t('title')}</Typography>
        <Button
          variant="contained"
          startIcon={<PersonAddIcon />}
          onClick={() => navigate('/users/new')}
        >
          {t('add_user')}
        </Button>
      </Box>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>{t('id')}</TableCell>
              <TableCell>{t('username')}</TableCell>
              <TableCell>{t('email')}</TableCell>
              <TableCell>{t('name')}</TableCell>
              <TableCell>{t('phone')}</TableCell>
              <TableCell>{t('role')}</TableCell>
              <TableCell>{t('status')}</TableCell>
              <TableCell>{t('actions')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {users.map((user) => (
              <TableRow
                key={user.id}
                hover
                onClick={() => navigate(`/users/${user.id}`)}
                sx={{ cursor: 'pointer', '&:last-child td, &:last-child th': { border: 0 } }}
              >
                <TableCell>{user.id}</TableCell>
                <TableCell>{user.username}</TableCell>
                <TableCell>{user.email}</TableCell>
                <TableCell>{user.firstName} {user.lastName}</TableCell>
                <TableCell>{user.phone || '-'}</TableCell>
                <TableCell>{getRoleChip(user.roleName)}</TableCell>
                <TableCell>
                  <Chip
                    label={user.isActive ? t('active') : t('inactive')}
                    color={user.isActive ? 'success' : 'default'}
                    size="small"
                  />
                </TableCell>
                <TableCell>
                  <IconButton
                    size="small"
                    onClick={(e) => { e.stopPropagation(); navigate(`/users/${user.id}`); }}
                  >
                    <EditIcon />
                  </IconButton>
                  <IconButton
                    size="small"
                    onClick={(e) => { e.stopPropagation(); handleDelete(user); }}
                    color="error"
                  >
                    <DeleteIcon />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mt: 2 }}>
        <Typography variant="body2" color="text.secondary">
          {t('showing_users', { shown: users.length, total: totalElements })}
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

      {/* Delete Confirmation Dialog */}
      <Dialog open={deleteDialogOpen} onClose={() => setDeleteDialogOpen(false)}>
        <DialogTitle>{t('delete_title')}</DialogTitle>
        <DialogContent>
          <Typography>
            {t('delete_confirm', { username: selectedUser?.username })}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>{t('cancel')}</Button>
          <Button onClick={confirmDelete} variant="contained" color="error">
            {t('delete')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default Users;
