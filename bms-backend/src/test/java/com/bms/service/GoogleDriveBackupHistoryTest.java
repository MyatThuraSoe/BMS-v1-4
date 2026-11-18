package com.bms.service;

import com.bms.service.GoogleDriveService.DriveBackupFile;
import com.google.api.client.util.DateTime;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restore list and the retention pass are the two places that read the whole
 * backup folder, and both of them used to see only the first fifty files. A shop
 * whose history is longer than that could not reach its older backups, and the
 * folder never got smaller — until the Google account filled up and every upload
 * started failing. So these tests pin the paging down, and pin down exactly which
 * files the cleanup is allowed to delete.
 */
class GoogleDriveBackupHistoryTest {

    private static final String NO_TIME = null;

    private static File driveFile(String id, String name, String modifiedTime) {
        File file = new File().setId(id).setName(name).setSize(1024L);
        if (modifiedTime != null) {
            file.setModifiedTime(new DateTime(modifiedTime));
        }
        return file;
    }

    private static DriveBackupFile backup(String id, String name, String modifiedTime) {
        return new DriveBackupFile(id, name, 1024L, modifiedTime, "application/json");
    }

    /** The two names this app gives its own backups, oldest on day 1. */
    private static DriveBackupFile exported(int day) {
        return backup("json-" + day, "LumiPOS_Backup_2026-11-" + pad(day) + "_020000.json", time(day, "02"));
    }

    private static DriveBackupFile snapshotted(int day) {
        return backup("db-" + day, "LumiPOS_DB_2026-11-" + pad(day) + "_020000.db", time(day, "03"));
    }

    /** A copy somebody asked for before a change, in the name form the app writes. */
    private static DriveBackupFile keptByHand(int day) {
        return backup("hand-json-" + day,
                "LumiPOS_Backup_2026-11-" + pad(day) + "_142000_manual.json", time(day, "14"));
    }

    private static String pad(int day) {
        return String.format("%02d", day);
    }

    private static String time(int day, String hour) {
        return "2026-11-" + pad(day) + "T" + hour + ":00:00Z";
    }

    // ------------------------------------------------------------------- listing

    @Test
    void aLongFolderIsListedToTheEndAndNotJustItsFirstPage() throws Exception {
        List<File> all = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            all.add(driveFile("f" + i, "LumiPOS_Backup_2026-11-17_0200" + i + ".json",
                    "2026-11-17T02:00:00Z"));
        }
        Queue<FileList> pages = new ArrayDeque<>(List.of(
                new FileList().setFiles(all.subList(0, 50)).setNextPageToken("token-1"),
                new FileList().setFiles(all.subList(50, 100)).setNextPageToken("token-2"),
                new FileList().setFiles(all.subList(100, 120))));

        List<String> askedFor = new ArrayList<>();
        List<DriveBackupFile> listed = GoogleDriveService.readAllPages(token -> {
            askedFor.add(token);
            return pages.remove();
        });

        assertEquals(120, listed.size(), "every backup the shop has is offered for restore");
        assertEquals("f0", listed.get(0).id(), "the order Drive was asked for is kept");
        assertEquals("f119", listed.get(119).id());
        assertEquals(Arrays.asList(null, "token-1", "token-2"), askedFor,
                "each page is asked for by its token");
    }

    @Test
    void anEmptyFolderIsAnEmptyListAndNotAnError() throws Exception {
        List<DriveBackupFile> listed = GoogleDriveService.readAllPages(
                token -> new FileList().setFiles(List.of()));

        assertTrue(listed.isEmpty());
    }

    @Test
    void aFolderIsNeverWalkedPastItsAgreedCeiling() throws Exception {
        // Without a ceiling, a folder of tens of thousands of files would keep the
        // restore list — and the cleanup behind it — paging until the connection
        // gave up, so the shop never sees a list at all.
        Queue<FileList> endless = new ArrayDeque<>();
        for (int page = 0; page < 20; page++) {
            List<File> files = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                files.add(driveFile("p" + page + "-" + i, "LumiPOS_Backup_x.json", "2026-11-17T02:00:00Z"));
            }
            endless.add(new FileList().setFiles(files).setNextPageToken("token-" + page));
        }

        List<DriveBackupFile> listed = GoogleDriveService.readAllPages(token -> endless.remove());

        assertEquals(500, listed.size(), "the walk stops at the agreed ceiling");
    }

    // ------------------------------------------------------------------ retention

    @Test
    void theOldestBackupsBeyondTheCountTheShopKeepsAreDeleted() {
        List<DriveBackupFile> files = new ArrayList<>();
        for (int day = 1; day <= 35; day++) {
            files.add(exported(day));
        }

        List<String> doomed = GoogleDriveService.filesToPrune(files, 30, Set.of("json-35"));

        assertEquals(List.of("json-1", "json-2", "json-3", "json-4", "json-5"), doomed,
                "the five oldest go, oldest first");
    }

    @Test
    void databasesAndExportsAreCountedSeparately() {
        List<DriveBackupFile> files = new ArrayList<>();
        for (int day = 1; day <= 4; day++) {
            files.add(snapshotted(day));
            files.add(exported(day));
        }

        List<String> doomed = GoogleDriveService.filesToPrune(files, 3, Set.of());

        assertEquals(Set.of("json-1", "db-1"), Set.copyOf(doomed),
                "each kind gets its own copy of the count the shop asked for");
    }

    @Test
    void aFileTheAppDidNotNameIsNeverDeleted() {
        List<DriveBackupFile> files = List.of(
                backup("holiday", "holiday-photo.db", time(9, "02")),
                backup("notes", "notes.json", time(8, "02")),
                exported(3), exported(2), exported(1));

        List<String> doomed = GoogleDriveService.filesToPrune(files, 1, Set.of());

        assertEquals(List.of("json-1", "json-2"), doomed,
                "the folder is the shop's; only what this app uploaded is its to delete");
    }

    @Test
    void theFileThatWasJustUploadedSurvivesTheCleanup() {
        // Drive stamps the copy with its own clock, so the file that just arrived
        // is not necessarily the newest one the listing reports. Today's backup is
        // protected by its id, not by a guess about ordering.
        List<DriveBackupFile> files = List.of(exported(3), exported(2), exported(1));

        assertEquals(List.of(), GoogleDriveService.filesToPrune(files, 2, Set.of("json-1")));
        assertEquals(List.of("json-1"), GoogleDriveService.filesToPrune(files, 2, Set.of()),
                "the protection is what saved it");
    }

    @Test
    void aFolderWithinItsCountIsLeftAlone() {
        assertEquals(List.of(), GoogleDriveService.filesToPrune(
                List.of(exported(2), exported(1)), 30, Set.of("json-2")));
    }

    @Test
    void aFileDriveGaveNoTimeToIsTreatedAsTheOldestInTheFolder() {
        List<DriveBackupFile> files = List.of(exported(2), backup("no-time", "LumiPOS_Backup_x.json", NO_TIME));

        List<String> doomed = GoogleDriveService.filesToPrune(files, 1, Set.of());

        assertEquals(List.of("no-time"), doomed,
                "a file whose age Drive will not say is the safest one to give up on");
    }

    @Test
    void aSealedBackupIsStillCountedAsTheKindOfBackupItIs() {
        // Encryption only wraps a file. Retention is about how many nights back a
        // shop can reach, so a sealed night counts the same as an open one.
        List<DriveBackupFile> files = List.of(
                backup("db-3", "LumiPOS_DB_2026-11-03_020000.db.enc", time(3, "03")),
                backup("db-2", "LumiPOS_DB_2026-11-02_020000.db", time(2, "03")),
                backup("db-1", "LumiPOS_DB_2026-11-01_020000.db.enc", time(1, "03")),
                backup("notes", "shopping-list.txt.enc", time(1, "01")));

        assertEquals(List.of("db-1"), GoogleDriveService.filesToPrune(files, 2, Set.of()),
                "the sealed names are counted, and a file this app did not name is still untouched");
        assertTrue(files.get(0).isDatabaseSnapshot(),
                "the restore list shows a sealed snapshot as a snapshot, not as something unknown");
    }

    // ------------------------------------------------------------------ keep forever

    @Test
    void aBackupTakenByHandIsNeverPruned() {
        // The copy a shop made on purpose before a big change is the one it comes
        // back for, and it comes back for it a month later. Retention is about the
        // nightly history, so a hand-made copy leaves the counting entirely.
        List<DriveBackupFile> files = new ArrayList<>();
        for (int day = 1; day <= 35; day++) {
            files.add(exported(day));
        }
        files.add(keptByHand(2));
        files.add(backup("hand-db", "LumiPOS_DB_2026-11-09_142000_manual.db.enc", time(9, "14")));

        List<String> doomed = GoogleDriveService.filesToPrune(files, 30, Set.of("json-35"));

        assertEquals(List.of("json-1", "json-2", "json-3", "json-4", "json-5"), doomed,
                "the nightly history thins exactly as it did before the hand-made copies arrived");
    }

    @Test
    void aKeptByHandCopyDoesNotUseUpASlotOfItsOwnKind() {
        List<DriveBackupFile> files = List.of(
                exported(4), keptByHand(3), exported(3), exported(2), exported(1));

        List<String> doomed = GoogleDriveService.filesToPrune(files, 3, Set.of());

        assertEquals(List.of("json-1"), doomed,
                "protecting one copy must not cost the shop a night of its ordinary history");
    }

    @Test
    void theRestoreListSaysWhoAskedForACopyAndWhetherItIsSealed() throws Exception {
        DriveBackupFile handSealed = backup("hand", "LumiPOS_DB_2026-11-17_142000_manual.db.enc", time(17, "14"));
        DriveBackupFile nightly = exported(17);

        assertTrue(handSealed.isManual());
        assertFalse(nightly.isManual());
        assertTrue(handSealed.isEncrypted());
        assertFalse(nightly.isEncrypted());
        assertTrue(handSealed.isDatabaseSnapshot(), "the marker sits before the extension, not after it");

        String listed = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(List.of(handSealed));
        assertTrue(listed.contains("\"manual\":true"), listed);
        assertTrue(listed.contains("\"encrypted\":true"), listed);
    }

    // ---- the values that go inside a Drive search ----
    @Test
    void aFolderNameHoldingAQuoteIsStillOnlyAName() {
        assertEquals("mimeType='application/vnd.google-apps.folder' and name='BMS\\'Backups'"
                        + " and trashed=false",
                GoogleDriveService.folderSearchQuery("BMS'Backups"),
                "a quote inside the value must not close the quote around it");
    }

    @Test
    void aBackslashIsDoubledBeforeTheQuoteIsEscaped() {
        // Backslash first: escaping the quote first would leave the new backslash of
        // that escape unescaped, and the value would read as something else again.
        assertEquals("a\\\\b\\'c", GoogleDriveService.escapeQuery("a\\b'c"));
    }

    @Test
    void theBackupFolderIsSearchedByItsIdAndNothingElse() {
        assertEquals("trashed=false and mimeType!='application/vnd.google-apps.folder'",
                GoogleDriveService.backupListQuery(null),
                "before a folder was ever made, the whole Drive is where the backups are");

        assertEquals("'0A1B2c3D4e5F6g7h8i9j' in parents and "
                        + "trashed=false and mimeType!='application/vnd.google-apps.folder'",
                GoogleDriveService.backupListQuery("0A1B2c3D4e5F6g7h8i9j"));
    }

    @Test
    void anIdThatIsNotAnIdIsRefusedBeforeDriveIsAsked() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> GoogleDriveService.backupListQuery("abc' or '1'='1"));
        assertTrue(thrown.getMessage().contains("folder id"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains("abc"),
                "whatever was in the setting is not repeated into the log or the screen: "
                        + thrown.getMessage());
    }
}
