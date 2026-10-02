package com.bms.service;

import com.bms.entity.BackupSetting;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongConsumer;

@Service
public class GoogleDriveService {

    @Value("${google.oauth.client-id}")
    private String clientId;

    @Value("${google.oauth.client-secret}")
    private String clientSecret;

    @Value("${google.oauth.redirect-uri}")
    private String redirectUri;

    private final BackupSettingRepository backupSettingRepository;

    public GoogleDriveService(BackupSettingRepository backupSettingRepository) {
        this.backupSettingRepository = backupSettingRepository;
    }

    public String getAuthorizationUrl() {
        try {
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    clientId,
                    clientSecret,
                    Collections.singleton(DriveScopes.DRIVE_FILE))
                    .setAccessType("offline") // Crucial for refresh token
                    .setApprovalPrompt("force")
                    .build();
            return flow.newAuthorizationUrl().setRedirectUri(redirectUri).build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Google authorization URL", e);
        }
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
                    .orElseThrow(() -> new IllegalStateException("Backup settings not found"));

            if (setting.getGoogleRefreshToken() == null || setting.getGoogleRefreshToken().isEmpty()) {
                throw new IllegalStateException("Google Drive is not connected. Please authorize first.");
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
                    throw new IllegalStateException("Google access was revoked or expired. Reconnect Google Drive in Settings — automatic backups are paused.");
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
            if (e instanceof IllegalStateException ise) {
                throw ise;
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
                .setQ("mimeType='application/vnd.google-apps.folder' and name='" + folderName + "' and trashed=false")
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
        Drive service = getDriveService();

        File fileMetadata = new File();
        fileMetadata.setName(uploadName(fileToUpload, fileName));

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

    /**
     * Lists the backup files sitting in the shop's backup folder, newest first.
     *
     * <p>Restore used to be impossible because Drive could only be written to.
     * Listing is what lets the settings page show a shop owner their history and
     * pick a point in time to come back to.
     *
     * <p>Trashed files are excluded so a deleted backup cannot be restored by
     * accident, and only files are returned (no sub-folders).
     */
    public List<DriveBackupFile> listBackupFiles(String folderId) throws Exception {
        Drive service = getDriveService();

        String query = "trashed=false and mimeType!='application/vnd.google-apps.folder'";
        if (folderId != null && !folderId.isBlank()) {
            query = "'" + folderId + "' in parents and " + query;
        }

        FileList result = service.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setOrderBy("modifiedTime desc")
                .setPageSize(50)
                .setFields("files(id, name, size, modifiedTime, mimeType)")
                .execute();

        List<DriveBackupFile> files = new ArrayList<>();
        if (result.getFiles() == null) {
            return files;
        }
        for (File f : result.getFiles()) {
            files.add(new DriveBackupFile(
                    f.getId(),
                    f.getName(),
                    f.getSize() == null ? 0L : f.getSize(),
                    f.getModifiedTime() == null ? null : f.getModifiedTime().toString(),
                    f.getMimeType()));
        }
        return files;
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
            return name != null && name.toLowerCase().endsWith(".json");
        }

        public boolean isDatabaseSnapshot() {
            return name != null && name.toLowerCase().endsWith(".db");
        }
    }
}