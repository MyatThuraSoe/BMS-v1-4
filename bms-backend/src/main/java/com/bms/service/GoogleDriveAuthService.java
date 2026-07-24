package com.bms.service;

import com.bms.entity.BackupSetting;
import com.bms.repository.BackupSettingRepository;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class GoogleDriveAuthService {

    @Value("${google.oauth.client-id}")
    private String clientId;

    @Value("${google.oauth.client-secret}")
    private String clientSecret;

    @Value("${google.oauth.redirect-uri}")
    private String redirectUri;

    private final BackupSettingRepository backupSettingRepository;

    public GoogleDriveAuthService(BackupSettingRepository backupSettingRepository) {
        this.backupSettingRepository = backupSettingRepository;
    }

    /**
     * Generates the Google OAuth 2.0 authorization URL for the user to click.
     */
    public String getAuthorizationUrl() {
        try {
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    clientId,
                    clientSecret,
                    Collections.singleton(DriveScopes.DRIVE_FILE))
                    .setAccessType("offline") // Crucial: ensures we get a refresh token
                    .setApprovalPrompt("force") // Forces the consent screen to ensure refresh token is returned
                    .build();

            return flow.newAuthorizationUrl().setRedirectUri(redirectUri).build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Google authorization URL", e);
        }
    }

    /**
     * Handles the callback from Google after the user grants permission.
     * Exchanges the authorization code for access and refresh tokens.
     */
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

    /**
     * Builds and returns an authenticated Google Drive service instance.
     * Automatically refreshes the access token if it has expired.
     */
    public Drive getDriveService() {
        try {
            BackupSetting setting = backupSettingRepository.findFirstByOrderByIdAsc()
                    .orElseThrow(() -> new IllegalStateException("Backup settings not found in database"));

            if (setting.getGoogleRefreshToken() == null || setting.getGoogleRefreshToken().isEmpty()) {
                throw new IllegalStateException("Google Drive is not connected. Please authorize first.");
            }

            // Use modern UserCredentials for 3-legged OAuth (Refresh Token flow)
            UserCredentials credentials = UserCredentials.newBuilder()
                    .setClientId(clientId)
                    .setClientSecret(clientSecret)
                    .setRefreshToken(setting.getGoogleRefreshToken())
                    .build();

            // This will automatically fetch a new access token if the current one is expired
            credentials.refreshIfExpired();

            // Save the fresh access token back to the database for future use
            setting.setGoogleAccessToken(credentials.getAccessToken().getTokenValue());
            backupSettingRepository.save(setting);

            // Build the Drive API client
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
}