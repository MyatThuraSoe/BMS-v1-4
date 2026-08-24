package com.bms.license;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/*
 * Machine identity used for license binding.
 *
 * Previously, a failed PowerShell probe returned the constant "UNKNOWN", so any
 * machine whose probe failed hashed to the same machine ID — one license would
 * unlock every such machine. Now a failed probe falls back to (1) the last known
 * good hardware value for that slot, or (2) a per-install random token persisted
 * in C:/LumiPOS. The fingerprint is therefore unique per physical install even
 * when the Windows/WMI probes cannot run.
 */
public final class MachineFingerprint {

    private static final Path CACHE_FILE = Path.of("C:/LumiPOS/machine.fingerprint");

    private MachineFingerprint() {}

    public static String getMachineId() {
        String guid = hardwareValue(
                "(Get-ItemProperty -Path 'HKLM:\\SOFTWARE\\Microsoft\\Cryptography' -Name MachineGuid).MachineGuid",
                "MachineGuid");
        String uuid = hardwareValue(
                "(Get-CimInstance -ClassName Win32_ComputerSystemProduct).UUID",
                "ProductUUID");

        String hash = sha256Hex(guid + "|" + uuid);
        return (hash.substring(0, 4) + "-" + hash.substring(4, 8) + "-"
                + hash.substring(8, 12) + "-" + hash.substring(12, 16)).toUpperCase();
    }

    private static String hardwareValue(String script, String slot) {
        String probe = exec("powershell", "-Command", script);
        if (probe != null && !probe.isBlank() && !"UNKNOWN".equals(probe)) {
            cacheSlot(slot, probe);
            return probe;
        }
        // Probe failed or returned nothing: last known good value, else per-install token.
        String cached = cachedSlot(slot);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        return installToken();
    }

    // ---- persisted cache helpers ----------------------------------------------

    private static synchronized void cacheSlot(String slot, String value) {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            Map<String, String> entries = loadCache();
            entries.put(slot, value);
            writeCache(entries);
        } catch (Exception e) {
            // Cache is best-effort; never break fingerprint calculation on IO errors.
        }
    }

    private static synchronized String cachedSlot(String slot) {
        try {
            return loadCache().get(slot);
        } catch (Exception e) {
            return null;
        }
    }

    private static String installToken() {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            Map<String, String> entries = loadCache();
            String token = entries.get("InstallToken");
            if (token == null || token.isBlank()) {
                byte[] bytes = new byte[32];
                new SecureRandom().nextBytes(bytes);
                token = hex(bytes);
                entries.put("InstallToken", token);
                writeCache(entries);
            }
            return token;
        } catch (Exception e) {
            // Absolute last resort: must never collide with a *constant*, so derive
            // from the runtime hash code of this class loader + random.
            byte[] bytes = new byte[16];
            new SecureRandom().nextBytes(bytes);
            return hex(bytes);
        }
    }

    private static Map<String, String> loadCache() throws Exception {
        Map<String, String> entries = new HashMap<>();
        if (!Files.exists(CACHE_FILE)) {
            return entries;
        }
        for (String line : Files.readAllLines(CACHE_FILE, StandardCharsets.UTF_8)) {
            int idx = line.indexOf('=');
            if (idx > 0) {
                entries.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }
        return entries;
    }

    private static void writeCache(Map<String, String> entries) throws Exception {
        StringBuilder sb = new StringBuilder();
        entries.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        Files.writeString(CACHE_FILE, sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- low-level helpers ----------------------------------------------------

    private static String exec(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line.trim());
            }
            process.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return hex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}