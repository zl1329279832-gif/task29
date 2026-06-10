package com.visitor.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class QrCodeUtil {

    private QrCodeUtil() {}

    /**
     * Generate a signed pass code: UUID + HMAC signature
     */
    public static String generatePassCode(String hmacKey) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String signature = hmacSign(uuid, hmacKey);
        return uuid + "." + signature;
    }

    /**
     * Verify a pass code's HMAC signature
     */
    public static boolean verifyPassCode(String code, String hmacKey) {
        if (code == null || !code.contains(".")) return false;
        String[] parts = code.split("\\.", 2);
        if (parts.length != 2) return false;
        String expectedSig = hmacSign(parts[0], hmacKey);
        return expectedSig.equals(parts[1]);
    }

    /**
     * Generate QR code as Base64 PNG string
     */
    public static String generateQrCodeBase64(String content, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", outputStream);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (WriterException | IOException e) {
            log.error("Failed to generate QR code: {}", e.getMessage());
            throw new RuntimeException("QR code generation failed", e);
        }
    }

    private static String hmacSign(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC signing failed", e);
        }
    }
}
