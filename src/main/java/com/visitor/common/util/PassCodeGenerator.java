package com.visitor.common.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;

@Component
public class PassCodeGenerator {

    private final String hmacSecret;

    public PassCodeGenerator(@Value("${passcode.hmac-secret}") String hmacSecret) {
        this.hmacSecret = hmacSecret;
    }

    public String generate(Long appointmentId) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        long timestamp = System.currentTimeMillis();
        String payload = appointmentId + ":" + uuid + ":" + timestamp;
        String signature = hmacSign(payload);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((payload + ":" + signature).getBytes(StandardCharsets.UTF_8));
    }

    public boolean verify(String code) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(code), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            if (parts.length != 4) return false;
            String payload = parts[0] + ":" + parts[1] + ":" + parts[2];
            String expectedSignature = hmacSign(payload);
            return expectedSignature.equals(parts[3]);
        } catch (Exception e) {
            return false;
        }
    }

    public Long extractAppointmentId(String code) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(code), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            return Long.parseLong(parts[0]);
        } catch (Exception e) {
            return null;
        }
    }

    private String hmacSign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC签名失败", e);
        }
    }
}
