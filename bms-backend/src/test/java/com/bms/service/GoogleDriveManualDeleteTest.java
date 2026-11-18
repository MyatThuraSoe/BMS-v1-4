package com.bms.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V6: keeping a backup forever is only half of protecting one. The other half is
 * that the shop can still throw it away, because a file nobody may delete is a file
 * that fills the Google account until every nightly backup starts failing.
 *
 * <p>Deleting by hand goes through the same two questions the cleanup asks: is this
 * a file this app named, and is it sitting in this app's folder. A folder id typed
 * wrongly in a request must not be a way to delete the shop's tax documents.
 */
class GoogleDriveManualDeleteTest {

    private static final String FOLDER = "0A1B2c3D4e5F6g7h8i9j";

    @Test
    void aCopyOfThisAppInTheBackupFolderIsTheShopsToDelete() {
        assertTrue(GoogleDriveService.isDeletableBackup(
                List.of(FOLDER), FOLDER, "LumiPOS_DB_2026-11-17_142000_manual.db.enc"));
        assertTrue(GoogleDriveService.isDeletableBackup(
                List.of("elsewhere", FOLDER), FOLDER, "LumiPOS_Backup_2026-11-17_020000.json"));
    }

    @Test
    void aFileOutsideTheBackupFolderIsLeftAlone() {
        assertFalse(GoogleDriveService.isDeletableBackup(List.of("other-folder"), FOLDER,
                "LumiPOS_DB_2026-11-17_020000.db"));
        assertFalse(GoogleDriveService.isDeletableBackup(List.of(), FOLDER,
                "LumiPOS_DB_2026-11-17_020000.db"));
        assertFalse(GoogleDriveService.isDeletableBackup(null, FOLDER,
                "LumiPOS_DB_2026-11-17_020000.db"));
        assertFalse(GoogleDriveService.isDeletableBackup(List.of(FOLDER), null,
                "LumiPOS_DB_2026-11-17_020000.db"),
                "before a folder was ever made there is nothing this app may call its own");
    }

    @Test
    void aFileThisAppDidNotNameIsNotABackupOfOurs() {
        assertFalse(GoogleDriveService.isDeletableBackup(List.of(FOLDER), FOLDER, "holiday-photo.db"));
        assertFalse(GoogleDriveService.isDeletableBackup(List.of(FOLDER), FOLDER, "notes.json"));
        assertFalse(GoogleDriveService.isDeletableBackup(List.of(FOLDER), FOLDER, "LumiPOS_DB_manual.txt"));
    }
}
