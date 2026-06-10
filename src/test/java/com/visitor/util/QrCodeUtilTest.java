package com.visitor.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QrCodeUtilTest {

    private static final String HMAC_KEY = "TestHmacKey2024";

    @Test
    void testGenerateAndVerifyPassCode() {
        String code = QrCodeUtil.generatePassCode(HMAC_KEY);
        assertNotNull(code);
        assertTrue(code.contains("."));
        assertTrue(QrCodeUtil.verifyPassCode(code, HMAC_KEY));
    }

    @Test
    void testVerifyPassCodeWithWrongKey() {
        String code = QrCodeUtil.generatePassCode(HMAC_KEY);
        assertFalse(QrCodeUtil.verifyPassCode(code, "WrongKey"));
    }

    @Test
    void testVerifyInvalidPassCode() {
        assertFalse(QrCodeUtil.verifyPassCode(null, HMAC_KEY));
        assertFalse(QrCodeUtil.verifyPassCode("", HMAC_KEY));
        assertFalse(QrCodeUtil.verifyPassCode("no-dot-separator", HMAC_KEY));
        assertFalse(QrCodeUtil.verifyPassCode("abc.invalidsig", HMAC_KEY));
    }

    @Test
    void testGenerateQrCodeBase64() {
        String base64 = QrCodeUtil.generateQrCodeBase64("test-content", 200);
        assertNotNull(base64);
        assertTrue(base64.startsWith("data:image/png;base64,"));
    }

    @Test
    void testPassCodeUniqueness() {
        String code1 = QrCodeUtil.generatePassCode(HMAC_KEY);
        String code2 = QrCodeUtil.generatePassCode(HMAC_KEY);
        assertNotEquals(code1, code2);
    }
}
