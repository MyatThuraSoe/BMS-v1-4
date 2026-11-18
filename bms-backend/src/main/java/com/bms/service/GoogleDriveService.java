package com.bms.service;

import com.bms.entity.BackupSetting;
import com.bms.exception.StateConflictException;
import com.bms.repository.BackupSettingRepository;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent; // <-- CORRECT IMPORT FOR FileContent
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;

@Service
public class GoogleDriveService {

    /** The two names this app gives its own backups; anything else is the shop's file. */
    public static final String JSON_BACKUP_PREFIX = "LumiPOS_Backup_";
    public static final String SNAPSHOT_PREFIX = "LumiPOS_DB_";

    /**
     * Marks the copies a person asked for, written before the extension:
     * {@code LumiPOS_DB_2026-11-17_142000_manual.db.enc}. Retention never thins
     * them and never counts them, because the shop keeps those by choice rather
     * than by habit — it is the copy taken before the big change, and losing it a
     * month of nightly backups later is the opposite of why it was made.
     */
    public static final String MANUAL_MARKER = "_manual";

    private static final int BACKUP_PAGE_SIZE = 100;

    /** The characters Drive itself uses in a file id, and the only ones accepted here. */
    private static final Pattern DRIVE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    @Autowired
    private ShopTimeService shopTimeService;

    // Every Google property carries a default: a shop that never touches cloud backup
    // has none of them set, and the app still has to start.
    @Value("${google.oauth.client-id:}")
    private String clientId;

    @Value("${google.oauth.client-secret:}")
    private String clientSecret;

    @Value("${google.oauth.redirect-uri:http://127.0.0.1:17234/api/backups/google/callback}")
    private String redirectUri;

    private final BackupSettingRepository backupSettingRepository;

    public GoogleDriveService(BackupSettingRepository backupSettingRepository) {
        this.backupSettingRepository = backupSettingRepository;
    }

    public void handleCallback(String code) {
        try {
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    clientId,
                    clientSecret,
                    Collections.singleton(DriveScopes.DRIVE_FILE))
                    .setAccessType("offline")
                    .build();

            GoogleTokenResponse response = flow.newTokenRequest(code)
                    .setRedirectUri(redirectUri)
                    .execute();

            BackupSetting setting = backupSettingRepository.findFirstByOrderByIdAsc()
                    .orElseGet(() -> backupSettingRepository.save(new BackupSetting()));

            setting.setGoogleAccessToken(response.getAccessToken());
            setting.setGoogleRefreshToken(response.getRefreshToken());
            backupSettingRepository.save(setting);

        } catch (Exception e) {
            throw new RuntimeException("Failed to handle Google OAuth callback", e);
        }
    }

    public Drive getDriveService() {
        try {
            BackupSetting setting = backupSettingRepository.findFirstByOrderByIdAsc()
                    .orElseThrow(() -> new StateConflictException("Backup settings not found"));

            if (setting.getGoogleRefreshToken() == null || setting.getGoogleRefreshToken().isEmpty()) {
                throw new StateConflictException("Google Drive is not connected. Please authorize first.");
            }

            // Use modern UserCredentials (replaces deprecated GoogleCredential)
            UserCredentials credentials = UserCredentials.newBuilder()
                    .setClientId(clientId)
                    .setClientSecret(clientSecret)
                    .setRefreshToken(setting.getGoogleRefreshToken())
                    .build();

            // Automatically fetch a new access token if the current one is expired
            try {
                credentials.refreshIfExpired();
            } catch (Exception refreshError) {
                // Token was revoked / expired on Google's side — surface it as a
                // clear, actionable error instead of a stack-trace soup.
                String msg = nestedMessages(refreshError);
                if (msg.contains("invalid_grant")) {
                    throw new StateConflictException("Google access was revoked or expired. Reconnect Google Drive in Settings — automatic backups are paused.");
                }
                throw refreshError;
            }

            // Save the fresh access token back to the database
            setting.setGoogleAccessToken(credentials.getAccessToken().getTokenValue());
            backupSettingRepository.save(setting);

            return new Drive.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("BMS-Backup")
                    .build();

        } catch (Exception e) {
            if (e instanceof StateConflictException sce) {
                throw sce;
            }
            throw new RuntimeException("Failed to initialize Google Drive service", e);
        }
    }

    private String nestedMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur.getMessage() != null) {
                sb.append(cur.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    private String uploadName(java.io.File fileToUpload, String fileName) {
        return fileName != null && !fileName.isBlank() ? fileName : fileToUpload.getName();
    }

    public String uploadFile(java.io.File fileToUpload, String mimeType) throws Exception {
        Drive service = getDriveService();

        File fileMetadata = new File();
        fileMetadata.setName(fileToUpload.getName());
        // Optional: fileMetadata.setParents(Collections.singletonList("YOUR_FOLDER_ID"));

        // FileContent is now correctly imported from com.google.api.client.http
        FileContent mediaContent = new FileContent(mimeType, fileToUpload);

        File uploadedFile = service.files().create(fileMetadata, mediaContent)
                .setFields("id, name, webViewLink")
                .execute();

        return uploadedFile.getWebViewLink();
    }

    /**
     * Finds or creates a dedicated "BMS_Backups" folder in Google Drive.
     */
    public String getOrCreateBackupFolderId(Drive service) throws Exception {
        String folderName = "BMS_Backups";

        // Search for existing folder
        FileList result = service.files().list()
                .setQ(folderSearchQuery(folderName))
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute();

        if (result.getFiles().isEmpty()) {
            // Create folder if it doesn't exist
            File folderMetadata = new File();
            folderMetadata.setName(folderName);
            folderMetadata.setMimeType("application/vnd.google-apps.folder");
            File folder = service.files().create(folderMetadata).setFields("id").execute();
            return folder.getId();
        }

        return result.getFiles().get(0).getId();
    }

    public String uploadFile(java.io.File fileToUpload, String mimeType, String folderId) throws Exception {
        Drive service = getDriveService();

        File fileMetadata = new File();
        fileMetadata.setName(uploadName(fileToUpload, null));

        // Save inside the specific folder
        if (folderId != null && !folderId.isEmpty()) {
            fileMetadata.setParents(Collections.singletonList(folderId));
        }

        FileContent mediaContent = new FileContent(mimeType, fileToUpload);
        File uploadedFile = service.files().create(fileMetadata, mediaContent)
                .setFields("id, name, webViewLink")
                .execute();

        return uploadedFile.getWebViewLink();
    }

    public String uploadFile(java.io.File fileToUpload, String mimeType, String folderId, String fileName) throws Exception {
        return uploadVerified(fileToUpload, mimeType, folderId, fileName).link();
    }

    /**
     * Uploads a backup and then asks Drive what it actually got, because a backup
     * that silently arrived truncated is worse than no backup at all: the shop
     * believes it can be restored and finds out on the worst day of its life that
     * the file is useless.
     *
     * <p>The proof is size plus MD5. Drive stopped publishing {@code md5Checksum}
     * for large files, so when it answers without one the size is still compared
     * and the missing hash is reported rather than treated as a mismatch.
     *
     * @throws IOException when Drive holds something other than what was sent; the
     *                     bad remote file is deleted before the throw so the
     *                     restore list cannot offer it
     */
    public DriveUpload uploadVerified(java.io.File fileToUpload, String mimeType, String folderId,
                                      String fileName) throws Exception {
        Drive service = getDriveService();

        File fileMetadata = new File();
        fileMetadata.setName(uploadName(fileToUpload, fileName));
        if (folderId != null && !folderId.isEmpty()) {
            fileMetadata.setParents(Collections.singletonList(folderId));
        }

        FileContent mediaContent = new FileContent(mimeType, fileToUpload);
        File uploadedFile = service.files().create(fileMetadata, mediaContent)
                .setFields("id, name, webViewLink")
                .execute();

        String id = uploadedFile.getId();
        long localSize = fileToUpload.length();
        String localMd5 = md5Of(fileToUpload);

        File stamp = service.files().get(id).setFields("id, size, md5Checksum").execute();
        String problem = verificationFailure(fileMetadata.getName(), localSize, localMd5,
                stamp.getSize(), stamp.getMd5Checksum());
        if (stamp.getMd5Checksum() == null || stamp.getMd5Checksum().isBlank()) {
            org.slf4j.LoggerFactory.getLogger(GoogleDriveService.class)
                    .info("Drive returned no checksum for {}; only its size was verified", id);
        }
        if (problem != null) {
            deleteQuietly(service, id);
            throw new IOException(problem);
        }

        return new DriveUpload(id, uploadedFile.getName(), uploadedFile.getWebViewLink());
    }

    /**
     * Compares what Drive says it stored with what was sent.
     *
     * @param remoteMd5 may be null or blank: Drive stopped publishing checksums
     *                  for large files, and a size match is then the best proof
     *                  available — not a reason to throw the backup away
     * @return the message for the shop, or null when the copy is sound
     */
    static String verificationFailure(String name, long localSize, String localMd5,
                                      Long remoteSize, String remoteMd5) {
        if (remoteSize != null && remoteSize != localSize) {
            return "Google Drive stored " + remoteSize + " of the " + localSize
                    + " bytes of " + name + ". The backup was discarded.";
        }
        if (remoteMd5 == null || remoteMd5.isBlank()) {
            return null;
        }
        if (!remoteMd5.equalsIgnoreCase(localMd5)) {
            return "Google Drive's copy of " + name
                    + " does not match the file that was sent. The backup was discarded.";
        }
        return null;
    }

    /** Deletes a Drive file; used to take a proved-bad backup out of the restore list. */
    public void deleteFile(String fileId) throws Exception {
        getDriveService().files().delete(fileId).execute();
    }

    /**
     * Deletes a file and reports whether Drive did it, because a delete that keeps
     * failing quietly is the difference between a folder that stays small and one
     * that fills the account.
     */
    private boolean deleteQuietly(Drive service, String fileId) {
        try {
            service.files().delete(fileId).execute();
            return true;
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(GoogleDriveService.class)
                    .warn("The Drive file {} could not be deleted: {}", fileId, ex.getMessage());
            return false;
        }
    }

    /** MD5 of the file as sent — the same value Drive reports back for it. */
    static String md5Of(java.io.File file) throws IOException {
        try (java.io.InputStream in = new java.io.BufferedInputStream(
                new java.io.FileInputStream(file), 64 * 1024)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IOException("MD5 is unavailable on this JVM: " + ex.getMessage(), ex);
        }
    }

    /**
     * What came back from an upload: enough to delete or re-fetch the file later.
     */
    public record DriveUpload(String id, String name, String link) {
    }

    /**
     * Lists the backup files sitting in the shop's backup folder, newest first.
     *
     * <p>Restore used to be impossible because Drive could only be written to.
     * Listing is what lets the settings page show a shop owner their history and
     * pick a point in time to come back to.
     *
     * <p>Trashed files are excluded so a deleted backup cannot be restored by
     * accident, and only files are returned (no sub-folders).
     *
     * <p>Drive answers a page at a time, so a shop with more history than one page
     * would otherwise only ever see its last fifty backups — and the cleanup that
     * counts from this list would leave the rest to grow forever.
     */
    public List<DriveBackupFile> listBackupFiles(String folderId) throws Exception {
        Drive service = getDriveService();

        return readAllPages(pageToken -> service.files().list()
                .setQ(backupListQuery(folderId))
                .setSpaces("drive")
                .setOrderBy("modifiedTime desc")
                .setPageSize(BACKUP_PAGE_SIZE)
                .setPageToken(pageToken)
                .setFields("nextPageToken, files(id, name, size, modifiedTime, mimeType)")
                .execute());
    }

    /** Drive's answer to "which folder holds the backups", with the name quoted safely. */
    static String folderSearchQuery(String folderName) {
        return "mimeType='application/vnd.google-apps.folder' and name='" + escapeQuery(folderName)
                + "' and trashed=false";
    }

    /**
     * The search that lists the shop's backups, scoped to its folder when there is
     * one. An id that is not an id is refused before it reaches Drive: the only way
     * a stored one stops looking like Drive's own characters is that somebody, or
     * something, put it there.
     */
    static String backupListQuery(String folderId) {
        String query = "trashed=false and mimeType!='application/vnd.google-apps.folder'";
        String folder = folderId == null || folderId.isBlank() ? null : validatedFolderId(folderId);
        return folder == null ? query : "'" + folder + "' in parents and " + query;
    }

    private static String validatedFolderId(String folderId) {
        if (!DRIVE_ID.matcher(folderId).matches()) {
            // The value is not repeated back: it is whatever ended up in the settings
            // row, and that is not something to write into a log line or a screen.
            throw new IllegalArgumentException("The saved Google Drive backup folder id is not a folder id");
        }
        return escapeQuery(folderId);
    }

    /**
     * Drive's search language takes values inside single quotes, so a value holding
     * a quote ends the string it sits in and the rest of it becomes the query. The
     * backslash goes first, because escaping the quote would otherwise escape the
     * backslash of its own escape.
     */
    static String escapeQuery(String value) {
        return value == null ? null : value.replace("\\", "\\\\").replace("'", "\\'");
    }

    static List<DriveBackupFile> readAllPages(BackupPageReader page) throws Exception {
        List<DriveBackupFile> files = new ArrayList<>();
        String pageToken = null;
        do {
            FileList result = page.read(pageToken);
            if (result.getFiles() != null) {
                for (File f : result.getFiles()) {
                    files.add(new DriveBackupFile(
                            f.getId(),
                            f.getName(),
                            f.getSize() == null ? 0L : f.getSize(),
                            f.getModifiedTime() == null ? null : f.getModifiedTime().toString(),
                            f.getMimeType()));
                }
            }
            pageToken = result.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank());
        return files;
    }

    /** One page of a folder listing, asked for by the token of the page before it. */
    @FunctionalInterface
    interface BackupPageReader {
        FileList read(String pageToken) throws Exception;
    }

    /**
     * Thins the backup folder down to the newest {@code keepPerKind} files of each
     * kind, deleting what is left over.
     *
     * <p>Files this app did not name are never touched: the folder is the shop's
     * and the admin may keep anything else in it. The copies uploaded by this run
     * are protected by their ids rather than by where the listing happened to
     * sort them, because Drive stamps files with its own clock.
     *
     * @param justUploaded ids of the files this backup has only now written
     * @return the ids to delete, oldest first
     */
    static List<String> filesToPrune(List<DriveBackupFile> files, int keepPerKind, Set<String> justUploaded) {
        List<DriveBackupFile> newestFirst = new ArrayList<>(files);
        newestFirst.sort(Comparator.comparing(DriveBackupFile::modifiedTime,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());

        Map<String, Integer> counted = new HashMap<>();
        List<String> doomed = new ArrayList<>();
        for (DriveBackupFile file : newestFirst) {
            String kind = backupKindOf(file);
            if (kind == null || file.isManual()) {
                // A hand-made copy is not part of the history being thinned, and it
                // does not use up one of the nights either.
                continue;
            }
            // Today's copy counts toward the count it is keeping: excluding it
            // would throw one of the shop's backups away for nothing.
            if (counted.merge(kind, 1, Integer::sum) > keepPerKind
                    && !justUploaded.contains(file.id())) {
                doomed.add(file.id());
            }
        }
        Collections.reverse(doomed);
        return doomed;
    }

    /** Which kind of backup this is, or null when the app did not name the file. */
    private static String backupKindOf(DriveBackupFile file) {
        return backupKindOfName(file.name());
    }

    private static String backupKindOfName(String fileName) {
        String name = stripEncryptionSuffix(fileName);
        if (name == null) {
            return null;
        }
        // A sealed backup is still the same kind of backup: the ".enc" is a wrapper
        // on the outside of it, so retention counts and thins it exactly as it
        // would the plain copy it replaced.
        String lower = name.toLowerCase();
        if (name.startsWith(JSON_BACKUP_PREFIX) && lower.endsWith(".json")) {
            return "json";
        }
        if (name.startsWith(SNAPSHOT_PREFIX) && lower.endsWith(".db")) {
            return "db";
        }
        return null;
    }

    /** Drops the encryption marker so the real kind of file shows through. */
    private static String stripEncryptionSuffix(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase();
        return lower.endsWith(BackupEncryption.ENCRYPTED_SUFFIX)
                ? name.substring(0, name.length() - BackupEncryption.ENCRYPTED_SUFFIX.length())
                : name;
    }

    /** Reads the hand-made marker out of a Drive file name. */
    static boolean isManual(String name) {
        return name != null && name.toLowerCase().contains(MANUAL_MARKER);
    }

    /**
     * Whether a delete asked for by hand may take this file. The questions are the
     * two the cleanup already asks — this app's folder, and a name this app gave a
     * backup — because a file id in a URL is otherwise the shop's whole Drive to a
     * wrong click.
     */
    static boolean isDeletableBackup(List<String> parents, String folderId, String name) {
        if (folderId == null || parents == null || !parents.contains(folderId)) {
            return false;
        }
        return backupKindOfName(name) != null;
    }

    /**
     * Takes one named backup out of the shop's backup folder.
     *
     * <p>A manual copy is kept until the shop says otherwise, which is also the one
     * way its space ever comes back: a Drive account that fills up fails every
     * nightly upload, so a file nobody may delete is not a protected backup but a
     * shop with no backups at all.
     *
     * @throws StateConflictException when the file is not one of this app's backups
     *                                in this app's folder
     * @return whether Drive confirmed the delete
     */
    public boolean deleteBackupOfThisApp(String fileId) throws Exception {
        Drive service = getDriveService();
        String folderId = getOrCreateBackupFolderId(service);
        File metadata = service.files().get(fileId).setFields("id,name,parents").execute();
        String name = metadata == null ? null : metadata.getName();
        if (!isDeletableBackup(metadata == null ? null : metadata.getParents(), folderId, name)) {
            org.slf4j.LoggerFactory.getLogger(GoogleDriveService.class)
                    .warn("Refused to delete the Drive file {}, which is not a backup of this app", fileId);
            throw new StateConflictException(
                    "That file is not one of this app's backups in the LumiPOS backup folder.");
        }
        return deleteQuietly(service, fileId);
    }

    /**
     * Deletes everything in the backup folder beyond the newest {@code keepPerKind}
     * files of each kind.
     *
     * @return how many files were actually removed
     */
    public int pruneOldBackups(String folderId, int keepPerKind, Collection<String> justUploaded) throws Exception {
        List<String> doomed = filesToPrune(listBackupFiles(folderId), keepPerKind, Set.copyOf(justUploaded));
        if (doomed.isEmpty()) {
            return 0;
        }
        Drive service = getDriveService();
        int deleted = 0;
        for (String id : doomed) {
            if (deleteQuietly(service, id)) {
                deleted++;
            }
        }
        if (deleted != doomed.size()) {
            org.slf4j.LoggerFactory.getLogger(GoogleDriveService.class)
                    .warn("Only {} of the {} old backups in Google Drive could be deleted",
                            deleted, doomed.size());
        }
        return deleted;
    }


    /**
     * Downloads a Drive file to a local file, reporting progress as it goes.
     *
     * <p>Progress is measured by counting bytes read against the size Drive
     * reported, which avoids depending on the transport's internal progress
     * listener. The stream is read in chunks and only the destination is
     * renamed into place at the end, so a download that dies halfway cannot be
     * mistaken for a complete backup.
     *
     * @param progress receives bytes written so far, may be null
     */
    public void downloadFile(String fileId, java.io.File destination, LongConsumer progress) throws Exception {
        Drive service = getDriveService();

        File meta = service.files().get(fileId).setFields("id, name, size").execute();
        long total = meta.getSize() == null ? -1L : meta.getSize();

        java.io.File partial = new java.io.File(destination.getAbsolutePath() + ".part");

        try (java.io.InputStream in = service.files().get(fileId).executeMediaAsInputStream();
             java.io.OutputStream out = new java.io.BufferedOutputStream(
                     new java.io.FileOutputStream(partial), 64 * 1024)) {

            byte[] buffer = new byte[64 * 1024];
            long written = 0L;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                written += read;
                if (progress != null) {
                    progress.accept(written);
                }
            }
        }

        if (total > 0) {
            long actual = partial.length();
            if (actual != total) {
                // Delete the truncated file: a partial download restored over a
                // live database would be far worse than a failed restore.
                partial.delete();
                throw new IOException("Incomplete download from Google Drive: expected "
                        + total + " bytes, received " + actual);
            }
        }

        if (destination.exists() && !destination.delete()) {
            partial.delete();
            throw new IOException("Could not replace existing file " + destination.getAbsolutePath());
        }
        if (!partial.renameTo(destination)) {
            partial.delete();
            throw new IOException("Could not move the downloaded file into place");
        }
    }

    /**
     * A backup file as shown in the restore list.
     */
    public record DriveBackupFile(String id, String name, long sizeBytes,
                                  String modifiedTime, String mimeType) {
        public boolean isJsonBackup() {
            String base = stripEncryptionSuffix(name);
            return base != null && base.toLowerCase().endsWith(".json");
        }

        public boolean isDatabaseSnapshot() {
            String base = stripEncryptionSuffix(name);
            return base != null && base.toLowerCase().endsWith(".db");
        }

        /** True when a person asked for this copy, rather than the schedule. */
        public boolean isManual() {
            return GoogleDriveService.isManual(name);
        }

        /** True when the file only opens with the passphrase it was made with. */
        public boolean isEncrypted() {
            return name != null && name.toLowerCase().endsWith(BackupEncryption.ENCRYPTED_SUFFIX);
        }
    }
}