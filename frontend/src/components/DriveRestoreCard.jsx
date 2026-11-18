import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Divider,
  Checkbox,
  FormControlLabel,
  LinearProgress,
  List,
  ListItem,
  ListItemButton,
  ListItemText,
  Radio,
  RadioGroup,
  TextField,
  FormControl,
  FormLabel,
  Typography
} from '@mui/material';
import {
  CloudDownload as CloudDownloadIcon,
  Save as SaveIcon,
  Delete as DeleteIcon,
  Info as InfoIcon
} from '@mui/icons-material';
import { useTranslation } from 'react-i18next';
import { backupRestoreService } from '../api/services';
import { useAuth } from '../context/AuthContext';
import { notifySuccess, notifyError } from '../utils/notify';
import HelpTip from './HelpTip';

const POLL_INTERVAL_MS = 1000;

function formatBytes(bytes) {
  if (!bytes) return '';
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

function formatWhen(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleString();
}

/**
 * Which half of the shop a Drive row can put back: a .db row is the whole
 * database, a .json row is the exported rows without the accounting ledgers, the
 * closed periods or the invoice counters. The shop has to see that before it
 * clicks restore, not afterwards.
 *
 * A sealed file is still the same kind of backup underneath, so the .enc marker
 * comes off before the question is answered.
 */
function withoutEncryptionMarker(name) {
  const lower = (name || '').toLowerCase();
  return lower.endsWith('.enc') ? lower.slice(0, -'.enc'.length) : lower;
}

function backupKind(file) {
  const name = withoutEncryptionMarker(file?.name);
  if (name.endsWith('.db')) return 'database';
  if (name.endsWith('.json')) return 'export';
  return null;
}

/** Whether this row needs the passphrase it was made with before it can be read. */
function isSealed(file) {
  return Boolean(file?.encrypted);
}

/** Whether a person asked for this copy, which is what keeps it out of retention. */
function isManual(file) {
  return Boolean(file?.manual);
}

/**
 * Recovery: pull a backup down from Google Drive, or take a raw database
 * snapshot for the case where the whole PC is gone.
 *
 * Progress is polled from a server-side job rather than faked with a timer: a
 * restore that "finishes" while still copying is worse than a slow bar, because
 * the shop closes the app thinking the data is back.
 */
export default function DriveRestoreCard({ isConnected, encryption }) {
  const { t } = useTranslation(['settings', 'common']);
  const { isAdmin } = useAuth();
  const [files, setFiles] = useState([]);
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState(null);
  const [mode, setMode] = useState('REPLACE_ALL');
  // A Drive file's install id is inside it, which this card cannot read before
  // downloading it, so the merge here is confirmed by the person choosing.
  const [sameShopChecked, setSameShopChecked] = useState(false);
  const [job, setJob] = useState(null);
  const [confirm, setConfirm] = useState(null);
  const [deletion, setDeletion] = useState(null);
  const [deleting, setDeleting] = useState(false);
  const [restorePending, setRestorePending] = useState(false);
  const [damagedDatabase, setDamagedDatabase] = useState(null);
  // Typed, never stored: an encrypted backup has no other way open, so a lost
  // passphrase is a file nobody can read, this app included.
  const [passphrase, setPassphrase] = useState('');
  const pollRef = useRef(null);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  useEffect(() => stopPolling, [stopPolling]);

  const refreshPending = useCallback(async () => {
    try {
      const status = await backupRestoreService.getRestoreStatus();
      setRestorePending(Boolean(status.pending));
      setDamagedDatabase(status.damagedDatabase || null);
    } catch (err) {
      // Non-fatal: the banner is informational.
    }
  }, []);

  const dismissDamaged = async () => {
    try {
      await backupRestoreService.dismissDamagedDatabase();
      setDamagedDatabase(null);
    } catch (err) {
      notifyError(err.response?.data?.message || t('restore_status_failed'));
    }
  };

  useEffect(() => {
    refreshPending();
  }, [refreshPending]);

  const loadFiles = async () => {
    setLoading(true);
    try {
      const list = await backupRestoreService.listDriveFiles();
      setFiles(list || []);
    } catch (err) {
      notifyError(err.response?.data?.message || t('restore_drive_list_failed'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (isConnected) loadFiles();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isConnected]);

  const startPolling = (jobId) => {
    stopPolling();
    pollRef.current = setInterval(async () => {
      try {
        const status = await backupRestoreService.getJob(jobId);
        setJob(status);
        if (status.status !== 'RUNNING') {
          stopPolling();
          if (status.status === 'SUCCESS') {
            notifySuccess(status.step || t('restore_complete'));
            loadFiles();
            refreshPending();
          } else {
            notifyError(status.error || t('restore_failed'));
          }
        }
      } catch (err) {
        stopPolling();
        setJob(null);
        notifyError(t('restore_status_failed'));
      }
    }, POLL_INTERVAL_MS);
  };

  const startJsonRestore = () => {
    if (!selected) return;
    const file = selected;
    const secret = passphrase;
    setConfirm(null);
    setJob({ percent: 0, step: t('restore_starting'), status: 'RUNNING' });
    backupRestoreService
      .restoreFromDrive(file.id, file.sizeBytes, mode, secret, mode === 'MERGE' && sameShopChecked)
      .then((jobId) => startPolling(jobId))
      .catch((err) => {
        setJob(null);
        notifyError(err.response?.data?.message || t('restore_failed'));
      });
  };

  const startDatabaseRestore = () => {
    if (!selected) return;
    const file = selected;
    const secret = passphrase;
    setConfirm(null);
    setJob({ percent: 0, step: t('restore_starting'), status: 'RUNNING' });
    backupRestoreService
      .restoreDatabaseFromDrive(file.id, file.sizeBytes, secret)
      .then((jobId) => startPolling(jobId))
      .catch((err) => {
        setJob(null);
        notifyError(err.response?.data?.message || t('restore_failed'));
      });
  };

  const startSnapshot = () => {
    setJob({ percent: 0, step: t('snapshot_starting'), status: 'RUNNING' });
    backupRestoreService
      // Sealed when the shop switched encryption on and typed the passphrase on
      // this page; an unsealed snapshot is what the nightly schedule always gives.
      .createDatabaseSnapshot(encryption?.enabled ? encryption.passphrase : null)
      .then((jobId) => startPolling(jobId))
      .catch((err) => {
        setJob(null);
        notifyError(err.response?.data?.message || t('snapshot_failed'));
      });
  };

  const running = job?.status === 'RUNNING';
  const kind = backupKind(selected);
  const isJson = kind === 'export';
  const isDb = kind === 'database';

  // A manual copy is the one backup retention never touches, so this button is the
  // only way its space ever comes back — and a full Drive fails every nightly upload.
  const deleteSelectedCopy = () => {
    const file = deletion;
    if (!file) return;
    setDeleting(true);
    backupRestoreService
      .deleteDriveFile(file.id)
      .then(() => {
        notifySuccess(t('drive_delete_done'));
        setDeletion(null);
        if (selected?.id === file.id) setSelected(null);
        return loadFiles();
      })
      .catch((err) => notifyError(err.response?.data?.message || t('drive_delete_failed')))
      .finally(() => setDeleting(false));
  };

  return (
    <Card sx={{ mt: 3 }}>
      <CardContent>
        <Typography variant="h6" gutterBottom>
          {t('restore_from_drive')}
          <HelpTip helpKey="help_restore_from_drive" t={t} />
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {t('restore_from_drive_hint')}
        </Typography>

        {/* A staged raw restore is waiting for a restart. */}
        {restorePending && (
          <Alert severity="warning" icon={<InfoIcon />} sx={{ mb: 2 }}>
            {t('restore_pending_restart')}
          </Alert>
        )}

        {/* The restore just applied replaced a database that no longer opened. */}
        {damagedDatabase && (
          <Alert
            severity="error"
            icon={<InfoIcon />}
            sx={{ mb: 2 }}
            action={(
              <Button color="inherit" size="small" onClick={dismissDamaged}>
                {t('common:close')}
              </Button>
            )}
          >
            {t('database_was_damaged', { file: damagedDatabase })}
          </Alert>
        )}

        {/* License reality check: a restored database on a NEW machine still
            needs a new licence, because licences are locked to hardware. */}
        <Alert severity="info" icon={<InfoIcon />} sx={{ mb: 2 }}>
          {t('restore_new_machine_note')}
        </Alert>

        {!isConnected ? (
          <Alert severity="warning">{t('restore_connect_first')}</Alert>
        ) : (
          <>
            <Box sx={{ display: 'flex', gap: 1, mb: 2, flexWrap: 'wrap' }}>
              <Button
                onClick={loadFiles}
                disabled={loading || running}
                startIcon={loading ? <CircularProgress size={16} /> : <CloudDownloadIcon />}
              >
                {t('refresh_backup_list')}
              </Button>
              <Button
                onClick={startSnapshot}
                disabled={running}
                variant="outlined"
                startIcon={<SaveIcon />}
              >
                {t('take_database_snapshot')}
              </Button>
            </Box>

            {files.length === 0 && !loading && (
              <Typography variant="body2" color="text.secondary">{t('no_drive_backups')}</Typography>
            )}

            {files.length > 0 && (
              <List dense sx={{ maxHeight: 240, overflow: 'auto', border: 1, borderColor: 'divider', borderRadius: 1 }}>
                {files.map((file) => {
                  const canDelete = isAdmin() && isManual(file);
                  return (
                  <ListItem
                    key={file.id}
                    disablePadding
                    secondaryAction={canDelete && (
                      <Button
                        size="small"
                        color="error"
                        startIcon={<DeleteIcon />}
                        disabled={running || deleting}
                        onClick={() => setDeletion(file)}
                        sx={{ mr: 1 }}
                      >
                        {t('drive_delete_backup')}
                      </Button>
                    )}
                  >
                    <ListItemButton
                      selected={selected?.id === file.id}
                      onClick={() => {
                        setSelected(file);
                        // The settings page may already hold the phrase this shop
                        // types; starting a new selection starts from what is typed.
                        setPassphrase(encryption?.passphrase || '');
                      }}
                      disabled={running}
                      sx={{ pr: canDelete ? 13 : 2 }}
                    >
                      <ListItemText
                        primary={
                          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, minWidth: 0 }}>
                            <Typography variant="body2" noWrap>{file.name}</Typography>
                            {backupKind(file) && (
                              <Chip
                                size="small"
                                variant="outlined"
                                color={backupKind(file) === 'database' ? 'success' : 'default'}
                                sx={{ flexShrink: 0 }}
                                label={t(backupKind(file) === 'database' ? 'backup_kind_database' : 'backup_kind_export')}
                              />
                            )}
                            <Chip
                              size="small"
                              variant="outlined"
                              color={isManual(file) ? 'warning' : 'default'}
                              sx={{ flexShrink: 0 }}
                              label={t(isManual(file) ? 'backup_source_manual' : 'backup_source_auto')}
                            />
                            <Chip
                              size="small"
                              variant="outlined"
                              color={isSealed(file) ? 'secondary' : 'default'}
                              sx={{ flexShrink: 0 }}
                              label={t(isSealed(file) ? 'sealed_backup' : 'unsealed_backup')}
                            />
                          </Box>
                        }
                        secondary={`${formatWhen(file.modifiedTime)}${file.sizeBytes ? ' - ' + formatBytes(file.sizeBytes) : ''}`}
                      />
                    </ListItemButton>
                  </ListItem>
                  );
                })}
              </List>
            )}

            {selected && !running && (
              <Box sx={{ mt: 2 }}>
                {isJson && (
                  <FormControl sx={{ mb: 2 }}>
                    <FormLabel>{t('import_mode')}</FormLabel>
                    <RadioGroup row value={mode} onChange={(e) => setMode(e.target.value)}>
                      <FormControlLabel
                        value="REPLACE_ALL"
                        control={<Radio size="small" />}
                        label={t('restore_mode_replace')}
                      />
                      <FormControlLabel
                        value="MERGE"
                        control={<Radio size="small" />}
                        label={t('restore_mode_merge')}
                      />
                    </RadioGroup>
                    <Typography variant="caption" color="text.secondary">
                      {mode === 'REPLACE_ALL' ? t('restore_mode_replace_hint') : t('restore_mode_merge_hint')}
                    </Typography>
                    {mode === 'MERGE' && (
                      <Alert severity="warning" sx={{ mt: 1 }}>
                        <Typography variant="body2" gutterBottom>
                          {t('merge_drive_warning')}
                        </Typography>
                        <FormControlLabel
                          control={
                            <Checkbox
                              checked={sameShopChecked}
                              onChange={(e) => setSameShopChecked(e.target.checked)}
                            />
                          }
                          label={t('merge_same_shop_confirm')}
                        />
                      </Alert>
                    )}
                  </FormControl>
                )}

                <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
                  {isJson && (
                    <Button
                      variant="contained"
                      color="error"
                      onClick={() => setConfirm({ kind: 'json' })}
                      startIcon={<DeleteIcon />}
                    >
                      {t('restore_selected_backup')}
                    </Button>
                  )}
                  {isDb && (
                    <Button
                      variant="contained"
                      color="error"
                      onClick={() => setConfirm({ kind: 'database' })}
                      startIcon={<DeleteIcon />}
                    >
                      {t('restore_database_file')}
                    </Button>
                  )}
                </Box>
              </Box>
            )}

            {/* Determinate progress, straight from the server job. */}
            {job && (
              <Box sx={{ mt: 3 }}>
                <Divider sx={{ mb: 2 }} />
                <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                  <Typography variant="body2">{job.step}</Typography>
                  <Typography variant="body2" fontWeight="medium">{job.percent}%</Typography>
                </Box>
                <LinearProgress
                  variant="determinate"
                  value={job.percent}
                  color={job.status === 'FAILED' ? 'error' : job.status === 'SUCCESS' ? 'success' : 'primary'}
                  sx={{ height: 8, borderRadius: 4 }}
                />
                {job.status === 'SUCCESS' && job.kind === 'db-restore' && (
                  <Alert severity="warning" sx={{ mt: 2 }}>{t('restore_restart_now')}</Alert>
                )}
                {job.status === 'FAILED' && job.error && (
                  <Alert severity="error" sx={{ mt: 2 }}>{job.error}</Alert>
                )}
              </Box>
            )}
          </>
        )}
      </CardContent>

      {/* Confirm before anything overwrites shop data. */}
      <Dialog open={Boolean(confirm)} onClose={() => setConfirm(null)} maxWidth="sm" fullWidth>
        <DialogTitle>
          {confirm?.kind === 'database' ? t('restore_database_confirm_title') : t('restore_confirm_title')}
        </DialogTitle>
        <DialogContent>
          <DialogContentText>
            {confirm?.kind === 'database' ? t('restore_database_confirm_body') : t('restore_confirm_body')}
          </DialogContentText>
          {confirm?.kind === 'json' && mode === 'REPLACE_ALL' && (
            <Alert severity="error" sx={{ mt: 2 }}>{t('restore_replace_warning')}</Alert>
          )}
          {/* A sealed file cannot be checked, let alone restored, until it opens. */}
          {isSealed(selected) && (
            <TextField
              autoFocus
              fullWidth
              type="password"
              label={t('restore_passphrase')}
              value={passphrase}
              onChange={(e) => setPassphrase(e.target.value)}
              sx={{ mt: 2 }}
              helperText={t('restore_passphrase_hint')}
            />
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirm(null)}>{t('cancel')}</Button>
          <Button
            color="error"
            variant="contained"
            disabled={isSealed(selected) && !passphrase}
            onClick={confirm?.kind === 'database' ? startDatabaseRestore : startJsonRestore}
          >
            {t('yes_restore')}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Confirm before a kept-by-hand copy is thrown away for good. */}
      <Dialog open={Boolean(deletion)} onClose={() => setDeletion(null)} maxWidth="xs" fullWidth>
        <DialogTitle>{t('drive_delete_confirm_title')}</DialogTitle>
        <DialogContent>
          <DialogContentText>
            {t('drive_delete_confirm_body', { file: deletion?.name })}
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeletion(null)} disabled={deleting}>{t('cancel')}</Button>
          <Button color="error" variant="contained" disabled={deleting} onClick={deleteSelectedCopy}>
            {t('yes_delete_backup')}
          </Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}
