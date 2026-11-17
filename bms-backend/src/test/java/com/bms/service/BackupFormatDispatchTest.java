package com.bms.service;

import com.bms.entity.BackupSetting;
import com.bms.entity.SystemSetting;
import com.bms.exception.StateConflictException;
import com.bms.repository.BackupSettingRepository;
import com.bms.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the scheduler puts on Google Drive is the shop's only safety net, so these
 * tests pin down two things: that a SQLite shop gets a whole database (the only
 * backup that carries the ledgers, the closed periods and the invoice counters),
 * and that a partial failure is reported instead of being quietly called success.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackupFormatDispatchTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 2, 0);
    private static final String DB_LINK = "https://drive/db";
    private static final String JSON_LINK = "https://drive/json";

    @Mock private ShopTimeService shopTime;

    private Map<Class<?>, Object> fakes;
    private BackupService service;
    private GoogleDriveService drive;
    private DatabaseSnapshotService snapshots;
    private DataExportService exporter;
    private SystemSettingRepository settings;
    private BackupSettingRepository backupSettings;
    private BackupSetting setting;

    @BeforeEach
    void setUp() throws Exception {
        // BackupService takes the whole repository layer; the test only cares about
        // the four collaborators a backup actually goes through, so the rest are
        // filled in from the constructor's own parameter list.
        Constructor<?> ctor = BackupService.class.getDeclaredConstructors()[0];
        fakes = new LinkedHashMap<>();
        for (Class<?> type : ctor.getParameterTypes()) {
            fakes.put(type, mock(type));
        }
        service = newService();
        drive = (GoogleDriveService) fakes.get(GoogleDriveService.class);
        snapshots = (DatabaseSnapshotService) fakes.get(DatabaseSnapshotService.class);
        exporter = (DataExportService) fakes.get(DataExportService.class);
        settings = (SystemSettingRepository) fakes.get(SystemSettingRepository.class);
        backupSettings = (BackupSettingRepository) fakes.get(BackupSettingRepository.class);

        setting = new BackupSetting();
        setting.setGoogleRefreshToken("refresh-token");
        when(backupSettings.findFirstByOrderByIdAsc()).thenReturn(Optional.of(setting));
        when(backupSettings.save(any(BackupSetting.class))).thenAnswer(inv -> inv.getArgument(0));
        when(shopTime.now()).thenReturn(NOW);
        when(drive.getOrCreateBackupFolderId(any())).thenReturn("folder-1");
        when(snapshots.uploadSnapshotToDrive(eq(drive), eq("folder-1"), any(), any()))
                .thenReturn(new GoogleDriveService.DriveUpload("file-db",
                        "LumiPOS_DB_2026-11-17_020000.db", DB_LINK));
        when(drive.uploadVerified(any(), eq("application/json"), eq("folder-1"), any()))
                .thenReturn(new GoogleDriveService.DriveUpload("file-json",
                        "LumiPOS_Backup_2026-11-17_020000.json", JSON_LINK));
    }

    private BackupService newService() {
        BackupService created;
        try {
            created = (BackupService) BackupService.class
                    .getDeclaredConstructors()[0]
                    .newInstance(fakes.values().toArray());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        ReflectionTestUtils.setField(created, "shopTimeService", shopTime);
        return created;
    }

    private void format(String value) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(BackupService.BACKUP_FORMAT_SETTING);
        setting.setSettingValue(value);
        when(settings.findBySettingKey(BackupService.BACKUP_FORMAT_SETTING))
                .thenReturn(Optional.of(setting));
    }

    private void encryption(boolean enabled) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(BackupService.BACKUP_ENCRYPTION_SETTING);
        setting.setSettingValue(Boolean.toString(enabled));
        when(settings.findBySettingKey(BackupService.BACKUP_ENCRYPTION_SETTING))
                .thenReturn(Optional.of(setting));
    }

    // ----------------------------------------------------------------- the default

    @Test
    void aSqliteShopBacksUpItsWholeDatabase() throws Exception {
        when(snapshots.isSupported()).thenReturn(true);

        String link = service.executeGoogleDriveBackup(null, null);

        assertEquals(DB_LINK, link, "the automatic backup is the snapshot, not the export");
        verify(exporter, never()).exportAllAsJson();
        verify(drive, never()).uploadVerified(any(), any(), any(), any());
        assertEquals("SUCCESS", setting.getBackupStatus());
    }

    @Test
    void aShopWithoutRawSnapshotsFallsBackToTheExport() throws Exception {
        when(snapshots.isSupported()).thenReturn(false);
        when(exporter.exportAllToJsonFile()).thenReturn(Path.of("backup.json"));

        String link = service.executeGoogleDriveBackup(null, null);

        assertEquals(JSON_LINK, link);
        verify(snapshots, never()).uploadSnapshotToDrive(any(), any(), any(), any());
        assertEquals("SUCCESS", setting.getBackupStatus());
    }

    @Test
    void theAdminsChoiceBeatsTheEngineDefault() throws Exception {
        when(snapshots.isSupported()).thenReturn(true);
        format("json");
        when(exporter.exportAllToJsonFile()).thenReturn(Path.of("backup.json"));

        service.executeGoogleDriveBackup(null, null);

        verify(snapshots, never()).uploadSnapshotToDrive(any(), any(), any(), any());
        verify(drive).uploadVerified(any(), eq("application/json"), eq("folder-1"), any());
    }

    @Test
    void anUnparsableFormatSettingStillProducesABackup() throws Exception {
        when(snapshots.isSupported()).thenReturn(true);
        format("PROBABLY-A-TYPO");

        service.executeGoogleDriveBackup(null, null);

        verify(snapshots).uploadSnapshotToDrive(eq(drive), eq("folder-1"), any(), any());
    }

    // ---------------------------------------------------------------------- BOTH

    @Test
    void bothUploadsTheDatabaseAndTheExport() throws Exception {
        format("BOTH");
        when(exporter.exportAllToJsonFile()).thenReturn(Path.of("backup.json"));

        String link = service.executeGoogleDriveBackup(null, null);

        assertEquals(DB_LINK, link);
        verify(snapshots).uploadSnapshotToDrive(eq(drive), eq("folder-1"), any(), any());
        verify(drive).uploadVerified(any(), eq("application/json"), eq("folder-1"), any());
        assertEquals("SUCCESS", setting.getBackupStatus());
        assertTrue(setting.getNextBackupDate().isAfter(NOW),
                "the next run is scheduled from this one, not left stale");
    }

    @Test
    void aFailedSnapshotDoesNotCancelTheExport(@TempDir Path dir) throws Exception {
        format("BOTH");
        Path json = dir.resolve("backup.json");
        Files.write(json, "{\"data\":{}}".getBytes());
        when(exporter.exportAllToJsonFile()).thenReturn(json);
        when(snapshots.uploadSnapshotToDrive(any(), any(), any(), any()))
                .thenThrow(new IOException("Google Drive stored 12 of the 4096 bytes. The backup was discarded."));

        String link = service.executeGoogleDriveBackup(null, null);

        assertEquals(JSON_LINK, link, "the shop still gets the half of the backup that worked");
        verify(drive).uploadVerified(eq(json.toFile()), eq("application/json"), eq("folder-1"), any());
        assertEquals("PARTIAL", setting.getBackupStatus());
        assertTrue(setting.getLastErrorMessage().contains("database snapshot"),
                "the shop is told which half failed: " + setting.getLastErrorMessage());
    }

    @Test
    void aFailedExportDoesNotCancelTheSnapshot() throws Exception {
        format("BOTH");
        when(exporter.exportAllToJsonFile()).thenThrow(new IOException("Export failed: out of memory"));

        String link = service.executeGoogleDriveBackup(null, null);

        assertEquals(DB_LINK, link);
        assertEquals("PARTIAL", setting.getBackupStatus());
        assertTrue(setting.getLastErrorMessage().contains("data export"),
                "the shop is told which half failed: " + setting.getLastErrorMessage());
    }

    @Test
    void whenNeitherHalfWorksTheBackupFails() throws Exception {
        format("BOTH");
        when(snapshots.uploadSnapshotToDrive(any(), any(), any(), any()))
                .thenThrow(new IOException("snapshot failed"));
        when(exporter.exportAllToJsonFile()).thenThrow(new IOException("export failed"));

        assertThrows(Exception.class, () -> service.executeGoogleDriveBackup(null, null));
    }

    // ----------------------------------------------------------------- recorded

    @Test
    void theJsonFileNameCarriesTheHourAsWellAsTheDay(@TempDir Path dir) throws Exception {
        format("JSON");
        Path json = dir.resolve("backup.json");
        Files.write(json, "{\"data\":{}}".getBytes());
        when(exporter.exportAllToJsonFile()).thenReturn(json);

        service.executeGoogleDriveBackup(null, null);

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(drive).uploadVerified(eq(json.toFile()), eq("application/json"), eq("folder-1"), name.capture());
        assertEquals("LumiPOS_Backup_2026-11-17_020000.json", name.getValue(),
                "a shop that backs up twice on one day must not have one copy land on the other");
    }

    @Test
    void theExportFileIsCleanedUpEvenWhenTheUploadDies(@TempDir Path dir) throws Exception {
        format("JSON");
        Path json = dir.resolve("backup.json");
        Files.write(json, "{\"data\":{}}".getBytes());
        when(exporter.exportAllToJsonFile()).thenReturn(json);
        when(drive.uploadVerified(any(), any(), any(), any())).thenThrow(new IOException("network is down"));

        assertThrows(IOException.class, () -> service.executeGoogleDriveBackup(null, null));

        assertTrue(Files.notExists(json), "the backup takes the export's temp file away with it");
    }

    @Test
    void aDiscardedUploadIsRecordedAsFailedAndRetryable() throws Exception {
        when(snapshots.isSupported()).thenReturn(true);
        when(snapshots.uploadSnapshotToDrive(any(), any(), any(), any()))
                .thenThrow(new IOException("Google Drive's copy does not match the file that was sent."));

        assertThrows(Exception.class, () -> service.runBackupNow(null, null));

        assertEquals("FAILED", setting.getBackupStatus());
        assertTrue(setting.getLastErrorMessage().contains("does not match"),
                "was: " + setting.getLastErrorMessage());
        assertEquals(NOW.plusHours(1), setting.getNextBackupDate(),
                "a broken Drive gets a slow retry, not a hammering");
    }

    @Test
    void noDriveConnectionRefusesBeforeWritingAnything() throws Exception {
        setting.setGoogleRefreshToken(null);

        assertThrows(StateConflictException.class, () -> service.executeGoogleDriveBackup(null, null));

        verifyNoInteractions(exporter);
        verify(snapshots, never()).uploadSnapshotToDrive(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ retention

    @Test
    void theFolderIsThinnedToTheNumberOfBackupsTheShopKeeps() throws Exception {
        when(snapshots.isSupported()).thenReturn(true);
        setting.setRetentionCount(12);

        service.executeGoogleDriveBackup(null, null);

        verify(drive).pruneOldBackups(eq("folder-1"), eq(12), eq(List.of("file-db")));
    }

    @Test
    void aShopThatNeverChoseKeepsTheDefaultNumberOfBackups() throws Exception {
        when(snapshots.isSupported()).thenReturn(true);

        service.executeGoogleDriveBackup(null, null);

        verify(drive).pruneOldBackups(eq("folder-1"), eq(BackupService.DEFAULT_RETENTION_COUNT), any());
    }

    @Test
    void aNumberTooSmallToBeAHistoryIsRaisedToTheMinimum() {
        BackupSetting tiny = new BackupSetting();
        tiny.setRetentionCount(1);

        assertEquals(BackupService.MINIMUM_RETENTION_COUNT, BackupService.retentionCountOf(tiny));
        assertEquals(BackupService.DEFAULT_RETENTION_COUNT,
                BackupService.retentionCountOf(new BackupSetting()));
    }

    @Test
    void aFolderThatCannotBeTidiedIsStillABackedUpShop() throws Exception {
        when(snapshots.isSupported()).thenReturn(true);
        when(drive.pruneOldBackups(any(), anyInt(), any()))
                .thenThrow(new IOException("Google Drive refused the delete"));

        String link = service.executeGoogleDriveBackup(null, null);

        assertEquals(DB_LINK, link);
        assertEquals("SUCCESS", setting.getBackupStatus(),
                "cleanup is housekeeping; it does not get to call a good backup a failure");
    }

    // ---------------------------------------------------------------- encryption

    /**
     * The scheduler has nobody at the keyboard, and the passphrase is deliberately
     * not in the database, so a nightly backup is a plain one even with the switch
     * on. Sealing files nobody can ever open would be the worse failure.
     */
    @Test
    void anAutomaticRunHasNobodyToTypeThePassphraseSoItStaysPlain() throws Exception {
        when(snapshots.isSupported()).thenReturn(true);
        encryption(true);

        service.runBackupNow(null, null);

        verify(snapshots).uploadSnapshotToDrive(eq(drive), eq("folder-1"), any(), isNull());
    }

    @Test
    void aBackupTakenAtTheKeyboardSealsWhatItUploads() throws Exception {
        when(snapshots.isSupported()).thenReturn(true);
        encryption(true);

        service.runBackupNow(null, null, "open-sesame".toCharArray());

        verify(snapshots).uploadSnapshotToDrive(eq(drive), eq("folder-1"), any(),
                eq("open-sesame".toCharArray()));
    }

    @Test
    void aPassphraseWithoutTheSwitchSealsNothing(@TempDir Path dir) throws Exception {
        format("JSON");
        encryption(false);
        Path json = dir.resolve("backup.json");
        Files.write(json, "{\"data\":{}}".getBytes());
        when(exporter.exportAllToJsonFile()).thenReturn(json);

        service.runBackupNow(null, null, "open-sesame".toCharArray());

        verify(drive).uploadVerified(eq(json.toFile()), eq("application/json"), eq("folder-1"),
                eq("LumiPOS_Backup_2026-11-17_020000.json"));
    }

    @Test
    void aSealedExportIsUploadedUnderAnEncryptedName(@TempDir Path dir) throws Exception {
        format("BOTH");
        encryption(true);
        when(snapshots.isSupported()).thenReturn(true);
        Path json = dir.resolve("backup.json");
        Files.write(json, "{\"data\":{}}".getBytes());
        when(exporter.exportAllToJsonFile()).thenReturn(json);
        when(drive.uploadVerified(any(), eq("application/octet-stream"), eq("folder-1"), any()))
                .thenReturn(new GoogleDriveService.DriveUpload("file-enc",
                        "LumiPOS_Backup_2026-11-17_020000.json.enc", JSON_LINK));

        service.runBackupNow(null, null, "open-sesame".toCharArray());

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(drive).uploadVerified(any(), eq("application/octet-stream"), eq("folder-1"), name.capture());
        assertEquals("LumiPOS_Backup_2026-11-17_020000.json.enc", name.getValue(),
                "a restore has to know to ask for a passphrase before it opens the file");
        assertTrue(Files.notExists(json), "no unsealed copy of the shop is left lying around");
    }
}
