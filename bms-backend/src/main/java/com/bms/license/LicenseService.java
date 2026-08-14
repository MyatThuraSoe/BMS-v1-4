package com.bms.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;

@Service
public class LicenseService {

    // Your PUBLIC key (safe to ship inside the app)
    private static final String PUBLIC_KEY_B64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyHynMFxdneIu3MqCP2HS\n" +
            "x7MpPZGREqk9M+vLa6sda6VVancqvlkYGHgj/PguEoea6tPP5Am8tBQwQV7GIBBg\n" +
            "zBTDOtJkJyzjzvFjqS1e/eRq010niqitc3WS02NZoFuAROXbWJHh5VVMFPZ6WTsa\n" +
            "NkiwyYyvG2tTTPgALQQS4VnD9J552UblcTldovDPRgbpkzc+Eq3Y22zuGi42uLY8\n" +
            "sfIZxJgG1X1cCbkkbLbgNPfBSm0ySCb3+NnGfOL9rBgWwKR5jJpvGo5UXhlxS7DT\n" +
            "bQF7ACx83Mi1jxrLxpA/M+fkTygbwD9TNvGLgYgGWdZ1iCq3VGV23tfE45Iil8n+\n" +
            "UwIDAQAB";

    // Stored OUTSIDE the app folder → copying the app folder doesn't copy the license
    private static final Path LICENSE_FILE = Path.of("C:/LumiPOS/license.key");

    private final ObjectMapper mapper = new ObjectMapper();

    // ✅ NEW: In-memory cache to avoid reading the file on every API call
    private volatile Map<String, String> cachedLicense;

    public String machineId() {
        return MachineFingerprint.getMachineId();
    }

    public synchronized boolean activate(String licenseKey) {
        try {
            Map<String, String> payload = parseAndVerify(licenseKey.trim());

            // 🔒 THE COPY-PROTECTION CHECK:
            if (!machineId().equals(payload.get("machineId"))) return false;

            // ✅ Check expiry
            if (isExpired(payload)) return false;

            Files.createDirectories(LICENSE_FILE.getParent());
            Files.writeString(LICENSE_FILE, licenseKey.trim());
            this.cachedLicense = payload;   // ✅ Update cache
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isLicensed() {
        Map<String, String> payload = cachedLicense;
        if (payload == null) {
            payload = loadFromDisk();
            cachedLicense = payload;
        }
        // ✅ Now checks BOTH machine ID match AND expiry
        return payload != null
                && machineId().equals(payload.get("machineId"))
                && !isExpired(payload);
    }

    // ✅ NEW: Returns license details for the controller (plan, customer, days left)
    public Map<String, String> licenseInfo() {
        return cachedLicense != null ? cachedLicense : loadFromDisk();
    }

    // ✅ NEW: Helper to read and verify the license from disk
    private Map<String, String> loadFromDisk() {
        try {
            if (!Files.exists(LICENSE_FILE)) return null;
            return parseAndVerify(Files.readString(LICENSE_FILE).trim());
        } catch (Exception e) {
            return null;
        }
    }

    // ✅ NEW: Helper to check if a license payload is expired
    private boolean isExpired(Map<String, String> payload) {
        String expiry = payload.getOrDefault("expiresAt", "2099-12-31");
        try {
            return LocalDate.parse(expiry).isBefore(LocalDate.now());
        } catch (Exception e) {
            return true; // invalid date = expired
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseAndVerify(String licenseKey) throws Exception {
        String[] parts = licenseKey.split("\\.");
        if (parts.length != 2) throw new SecurityException("Malformed license key");

        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
        byte[] signature = Base64.getUrlDecoder().decode(parts[1]);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey());
        verifier.update(payloadBytes);
        if (!verifier.verify(signature)) throw new SecurityException("Invalid signature");

        return mapper.readValue(payloadBytes, Map.class);
    }

    private PublicKey publicKey() throws Exception {
        // Your key is raw base64 (no PEM headers), so just strip whitespace
        String cleaned = PUBLIC_KEY_B64
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] bytes = Base64.getDecoder().decode(cleaned);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
    }
}