package com.bms.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Validates image uploads by their content (magic bytes), never by the client-supplied
 * Content-Type header or file extension. Returns the trusted MIME type for storage so
 * that bytes served back inline can never be interpreted as HTML/JS (stored XSS).
 */
public final class ImageValidationUtil {

    private ImageValidationUtil() {
    }

    private static final long MAX_IMAGE_BYTES = 5_000_000L; // 5 MB; Spring multipart cap is typically lower

    /**
     * @return trusted MIME type (image/jpeg, image/png, image/webp, image/gif) or null if not a known image
     */
    public static String detectMimeType(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (data.length >= 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == '\r' && data[5] == '\n' && data[6] == 0x1A && data[7] == '\n') {
            return "image/png";
        }
        // JPEG: FF D8 FF
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // GIF: "GIF8"
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8') {
            return "image/gif";
        }
        // WebP: "RIFF" .... "WEBP"
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /**
     * Validates an uploaded multipart file as a real image by magic bytes.
     *
     * @return the trusted MIME type for storage
     * @throws com.bms.exception.BusinessException if the payload is too large or not a recognized image
     */
    public static String validateImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new com.bms.exception.BusinessException("No file provided");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new com.bms.exception.BusinessException("Image exceeds the maximum allowed size of 5 MB");
        }
        byte[] data = file.getBytes();
        String mime = detectMimeType(data);
        if (mime == null) {
            throw new com.bms.exception.BusinessException("Only JPEG, PNG, WebP, or GIF images are allowed");
        }
        return mime;
    }

    /** Maps a trusted MIME type to a safe lowercase file extension (without dot). */
    public static String mimeToExtension(String mime) {
        if (mime == null) {
            return "jpeg";
        }
        return switch (mime) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "jpeg";
        };
    }
}