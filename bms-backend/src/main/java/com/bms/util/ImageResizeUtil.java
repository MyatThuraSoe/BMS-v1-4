package com.bms.util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Resizes and compresses uploaded product images to reduce storage and transfer size.
 * Converts all formats to JPEG (product photos don't need transparency) and
 * scales down to a maximum dimension of 800 px, maintaining aspect ratio.
 * JPEG quality is set to 0.85 for a good balance of quality vs size.
 * No external libraries required — uses only javax.imageio / java.awt.
 */
public final class ImageResizeUtil {

    private ImageResizeUtil() {
    }

    private static final int MAX_DIMENSION = 800;
    private static final float JPEG_QUALITY = 0.85f;
    private static final String OUTPUT_FORMAT = "jpeg";

    /**
     * If the image exceeds MAX_DIMENSION on either side, resize it down and
     * re-encode as JPEG.  Returns the original bytes unchanged when:
     * <ul>
     *   <li>the image is already within bounds, or</li>
     *   <li>decoding fails (e.g. unsupported WebP — ImageIO cannot read WebP natively)</li>
     * </ul>
     *
     * @return optimised image bytes (JPEG) and the output extension "jpeg"
     */
    public static ResizeResult resizeIfLarger(byte[] original) throws IOException {
        if (original == null || original.length == 0) {
            return new ResizeResult(original, "jpeg");
        }

        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(original));
        } catch (IOException e) {
            return new ResizeResult(original, "jpeg");
        }
        if (src == null) {
            return new ResizeResult(original, "jpeg");
        }

        int w = src.getWidth();
        int h = src.getHeight();

        if (w <= MAX_DIMENSION && h <= MAX_DIMENSION) {
            // Already small — store as-is, but still re-encode to JPEG for
            // consistent format (PNG → JPEG saves space; JPEG → JPEG is near-idempotent).
            byte[] reencoded = toJpeg(src, JPEG_QUALITY);
            // Only replace if re-encoding actually shrank the file; otherwise keep original.
            return reencoded.length < original.length
                    ? new ResizeResult(reencoded, OUTPUT_FORMAT)
                    : new ResizeResult(original, OUTPUT_FORMAT);
        }

        // Scale down proportionally.
        double scale = Math.min((double) MAX_DIMENSION / w, (double) MAX_DIMENSION / h);
        int newW = (int) Math.round(w * scale);
        int newH = (int) Math.round(h * scale);

        BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,   RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, newW, newH, null);
        g.dispose();

        byte[] out = toJpeg(resized, JPEG_QUALITY);
        return new ResizeResult(out, OUTPUT_FORMAT);
    }

    /** Encode a BufferedImage as JPEG with the given quality (0–1). */
    private static byte[] toJpeg(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(OUTPUT_FORMAT);
        if (!writers.hasNext()) {
            // Fallback: ImageIO.write (no quality control, but at least produces output).
            ImageIO.write(image, OUTPUT_FORMAT, baos);
            return baos.toByteArray();
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.setOutput(new MemoryCacheImageOutputStream(baos));
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    /** Result of a resize operation: the optimised bytes and the output file extension. */
    public record ResizeResult(byte[] data, String extension) {
    }
}
