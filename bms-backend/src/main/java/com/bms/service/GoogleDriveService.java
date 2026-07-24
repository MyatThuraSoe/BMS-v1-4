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

import java.util.Collections;

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
            credentials.refreshIfExpired();

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
            throw new RuntimeException("Failed to initialize Google Drive service", e);
        }
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
        fileMetadata.setName(fileToUpload.getName());

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
}