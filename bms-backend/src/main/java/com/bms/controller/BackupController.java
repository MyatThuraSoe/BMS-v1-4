package com.bms.controller;

import com.bms.entity.BackupSetting;
import com.bms.repository.BackupSettingRepository;
import com.bms.service.BackupService;
import com.bms.service.GoogleDriveService;
import com.bms.dto.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/backups")
public class BackupController {

    private static final Duration STATE_TTL = Duration.ofMinutes(5);
    private static final int MAX_PENDING_STATES = 200;

    private final BackupSettingRepository backupSettingRepository;
    private final BackupService backupService;
    private final GoogleDriveService googleDriveService;
    private final Map<String, Instant> pendingOAuthStates = new ConcurrentHashMap<>();

    @Value("${google.oauth.client-id}")
    private String clientId;

    @Value("${google.oauth.redirect-uri}")
    private String redirectUri;

    @Value("${google.oauth.frontend-redirect-base}")
    private String frontendRedirectBase;

    public BackupController(BackupSettingRepository backupSettingRepository,
                            BackupService backupService,
                            GoogleDriveService googleDriveService) {
        this.backupSettingRepository = backupSettingRepository;
        this.backupService = backupService;
        this.googleDriveService = googleDriveService;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<BackupSetting>> getSettings() {
        BackupSetting setting = backupSettingRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> backupSettingRepository.save(new BackupSetting()));
        return ResponseEntity.ok(new ApiResponse<>(true, "Settings retrieved", maskTokens(setting)));
    }

    private BackupSetting maskTokens(BackupSetting source) {
        BackupSetting masked = new BackupSetting();
        masked.setId(source.getId());
        masked.setEnabled(source.isEnabled());
        masked.setFrequency(source.getFrequency());
        masked.setCustomCronExpression(source.getCustomCronExpression());
        masked.setLastBackupDate(source.getLastBackupDate());
        masked.setNextBackupDate(source.getNextBackupDate());
        masked.setGoogleRefreshToken(maskSecret(source.getGoogleRefreshToken()));
        masked.setGoogleAccessToken(maskSecret(source.getGoogleAccessToken()));
        return masked;
    }

    private String maskSecret(String token) {
        if (token == null || token.isEmpty()) {
            return token;
        }
        return token.length() <= 4 ? "••••" : "••••" + token.substring(token.length() - 4);
    }

    @PutMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<BackupSetting>> updateSettings(@RequestBody BackupSetting setting) {
        BackupSetting existing = backupSettingRepository.findFirstByOrderByIdAsc().orElse(new BackupSetting());
        existing.setEnabled(setting.isEnabled());
        existing.setFrequency(setting.getFrequency());
        existing.setCustomCronExpression(setting.getCustomCronExpression());

        if (existing.isEnabled() && existing.getNextBackupDate() == null) {
            LocalDateTime now = LocalDateTime.now();
            switch (existing.getFrequency().toUpperCase()) {
                case "DAILY": existing.setNextBackupDate(now.plusDays(1)); break;
                case "WEEKLY": existing.setNextBackupDate(now.plusWeeks(1)); break;
                case "MONTHLY": existing.setNextBackupDate(now.plusMonths(1)); break;
                case "YEARLY": existing.setNextBackupDate(now.plusYears(1)); break;
                case "CUSTOM": existing.setNextBackupDate(now.plusDays(1)); break;
            }
        } else if (!existing.isEnabled()) {
            existing.setNextBackupDate(null);
        }

        return ResponseEntity.ok(new ApiResponse<>(true, "Settings updated", backupSettingRepository.save(existing)));
    }

    // ✅ NEW: Returns the Google OAuth URL so the frontend can open it in the browser
    @GetMapping("/google/auth-url")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> getAuthUrl(jakarta.servlet.http.HttpServletRequest request) {
        // Single-use CSRF token; verified in the callback. Prevents login CSRF /
        // OAuth state poisoning by attackers who can start an auth flow themselves.
        String state = UUID.randomUUID().toString();
        evictExpiredStates();
        if (pendingOAuthStates.size() >= MAX_PENDING_STATES) {
            pendingOAuthStates.clear();
        }
        pendingOAuthStates.put(state, Instant.now().plus(STATE_TTL));

        String scope = "https://www.googleapis.com/auth/drive.file";
        String authUrl = "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + clientId
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&response_type=code"
                + "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + state;

        // The registered redirect URI is loopback (127.0.0.1) — Google will send
        // the consent result to the SERVER computer no matter which device the
        // admin started from. Tell the frontend so it can guide the user instead
        // of silently failing on a phone/LAN browser.
        boolean callerIsServer = false;
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host != null) {
            host = host.toLowerCase();
            host = host.substring(0, Math.max(0, host.indexOf(':') < 0 ? host.length() : host.indexOf(':')));
            callerIsServer = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
        }

        Map<String, String> data = new HashMap<>();
        data.put("authUrl", authUrl);
        data.put("callerIsServer", Boolean.toString(callerIsServer));
        data.put("serverCallbackUrl", redirectUri);
        return ResponseEntity.ok(new ApiResponse<>(true, "ok", data));
    }

    private void evictExpiredStates() {
        Instant now = Instant.now();
        pendingOAuthStates.entrySet().removeIf(e -> e.getValue().isBefore(now));
    }

    // ✅ NEW: Status endpoint for polling
    @GetMapping("/google/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        BackupSetting setting = backupSettingRepository.findFirstByOrderByIdAsc().orElse(new BackupSetting());
        Map<String, Object> status = new HashMap<>();
        status.put("connected", setting.getGoogleRefreshToken() != null && !setting.getGoogleRefreshToken().isEmpty());
        return ResponseEntity.ok(new ApiResponse<>(true, "ok", status));
    }

    // Callback from Google (NO @PreAuthorize - Google redirects browser directly here)
    @GetMapping("/google/callback")
    public ResponseEntity<Void> handleGoogleCallback(@RequestParam(value = "state", required = false) String state,
                                                     @RequestParam(value = "code", required = false) String code,
                                                     @RequestParam(value = "error", required = false) String error) {
        if (error != null && !error.isBlank()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendRedirectBase + "/settings/backup?status=error"))
                    .build();
        }
        // Require + consume the single-use state token (one-time use, 5-min TTL).
        if (state == null || code == null) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendRedirectBase + "/settings/backup?status=error"))
                    .build();
        }
        Instant issuedAt = pendingOAuthStates.remove(state);
        if (issuedAt == null || issuedAt.isBefore(Instant.now())) {
            org.slf4j.LoggerFactory.getLogger(BackupController.class)
                    .warn("Google OAuth callback rejected: missing/expired state token");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendRedirectBase + "/settings/backup?status=error"))
                    .build();
        }
        try {
            googleDriveService.handleCallback(code);
            // Redirect to friendly success page
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/api/backups/google/success"))
                    .build();
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(BackupController.class)
                    .error("Google OAuth token exchange failed", e);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendRedirectBase + "/settings/backup?status=error"))
                    .build();
        }
    }

    // ✅ NEW: Friendly "you can close this tab" page
    @GetMapping("/google/success")
    public ResponseEntity<String> successPage() {
        String html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>LumiPOS — Google Drive Connected</title>
                <style>
                    body { font-family: 'Segoe UI', sans-serif; display: flex; align-items: center;
                           justify-content: center; min-height: 100vh; margin: 0; background: #F3F5F1; }
                    .card { background: #fff; padding: 48px; border-radius: 16px; text-align: center;
                            box-shadow: 0 8px 30px rgba(0,0,0,0.08); max-width: 420px; }
                    .icon { font-size: 64px; margin-bottom: 16px; }
                    h1 { color: #2B6E4F; font-size: 22px; margin: 0 0 12px; }
                    p { color: #5B655D; line-height: 1.6; }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="icon">✅</div>
                    <h1>Google Drive Connected!</h1>
                    <p>Your backups are now linked to Google Drive.<br>
                       You can close this tab and return to <strong>LumiPOS</strong>.</p>
                </div>
            </body>
            </html>
            """;
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @PostMapping("/google/disconnect")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> disconnectGoogleDrive() {
        BackupSetting setting = backupSettingRepository.findFirstByOrderByIdAsc().orElse(null);
        if (setting != null) {
            setting.setGoogleAccessToken(null);
            setting.setGoogleRefreshToken(null);
            setting.setEnabled(false);
            backupSettingRepository.save(setting);
        }
        return ResponseEntity.ok(new ApiResponse<>(true, "Google Drive disconnected", null));
    }

    @PostMapping("/run-now")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> runBackupNow(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            String link = backupService.executeGoogleDriveBackup(startDate, endDate);
            String msg = (startDate != null && endDate != null)
                    ? "Filtered backup completed successfully!"
                    : "Full backup completed successfully!";
            return ResponseEntity.ok(new ApiResponse<>(true, msg, link));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ApiResponse<>(false, "Backup failed: " + e.getMessage(), null));
        }
    }

    // Local .xlsx download of all business data (no Google Drive needed)
    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportManualBackup() {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            backupService.exportBackup(baos, null, null);
            String filename = "LumiPOS_Backup_" + LocalDate.now() + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(org.springframework.http.MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(baos.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(null);
        }
    }
}