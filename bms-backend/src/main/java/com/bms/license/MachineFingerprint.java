package com.bms.license;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class MachineFingerprint {

    private MachineFingerprint() {}

    public static String getMachineId() {
        // Windows GUID + motherboard UUID = unique per physical PC
        String guid = exec("powershell", "-Command",
                "(Get-ItemProperty -Path 'HKLM:\\SOFTWARE\\Microsoft\\Cryptography' -Name MachineGuid).MachineGuid");
        String uuid = exec("powershell", "-Command",
                "(Get-CimInstance -ClassName Win32_ComputerSystemProduct).UUID");

        String hash = sha256Hex(guid + "|" + uuid);
        return (hash.substring(0, 4) + "-" + hash.substring(4, 8) + "-"
                + hash.substring(8, 12) + "-" + hash.substring(12, 16)).toUpperCase();
    }

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
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}