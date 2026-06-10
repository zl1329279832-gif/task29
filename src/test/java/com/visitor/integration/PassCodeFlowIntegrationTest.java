package com.visitor.integration;

import com.visitor.common.util.PassCodeGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PassCodeFlowIntegrationTest {

    private final PassCodeGenerator generator = new PassCodeGenerator("test-secret-key-for-integration-testing");

    @Test
    void generateAndVerify_success() {
        String code = generator.generate(1L);
        assertNotNull(code);
        assertTrue(generator.verify(code));
    }

    @Test
    void verify_tamperedCode_returnsFalse() {
        String code = generator.generate(1L);
        // Tamper with the code
        String tampered = code.substring(0, code.length() - 2) + "XX";
        assertFalse(generator.verify(tampered));
    }

    @Test
    void extractAppointmentId_success() {
        String code = generator.generate(42L);
        Long extractedId = generator.extractAppointmentId(code);
        assertEquals(42L, extractedId);
    }

    @Test
    void verify_invalidFormat_returnsFalse() {
        assertFalse(generator.verify("not-a-valid-code"));
        assertFalse(generator.verify(""));
    }

    @Test
    void generate_uniqueCodes() {
        String code1 = generator.generate(1L);
        String code2 = generator.generate(1L);
        assertNotEquals(code1, code2, "Each generated code should be unique");
    }

    @Test
    void generate_differentAppointments_differentCodes() {
        String code1 = generator.generate(1L);
        String code2 = generator.generate(2L);
        assertNotEquals(code1, code2);
    }
}
