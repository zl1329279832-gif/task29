package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.PassCodeMapper;
import com.visitor.model.entity.Appointment;
import com.visitor.model.entity.PassCode;
import com.visitor.model.enums.PassCodeStatusEnum;
import com.visitor.util.RedisLock;
import com.visitor.util.QrCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PassCodeServiceTest {

    @InjectMocks
    private PassCodeService passCodeService;

    @Mock private PassCodeMapper passCodeMapper;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private RedisLock redisLock;
    @Mock private ValueOperations<String, Object> valueOperations;

    private static final String HMAC_KEY = "TestHmacKey2024";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(passCodeService, "hmacKey", HMAC_KEY);
        ReflectionTestUtils.setField(passCodeService, "defaultMaxUses", 2);
        ReflectionTestUtils.setField(passCodeService, "qrSize", 200);
    }

    // ── Generate tests ──────────────────────────────────────────────────

    @Test
    void testGenerateForAppointment() {
        Appointment appointment = Appointment.builder()
                .id(1L)
                .expectedArrive(LocalDateTime.now().plusHours(1))
                .expectedLeave(LocalDateTime.now().plusHours(3))
                .build();

        when(passCodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(passCodeMapper.insert(any())).thenReturn(1);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        PassCode result = passCodeService.generateForAppointment(appointment);

        assertNotNull(result);
        assertEquals(1L, result.getAppointmentId());
        assertNotNull(result.getCode());
        assertTrue(result.getCode().contains("."));
        assertEquals(PassCodeStatusEnum.ACTIVE, result.getStatus());
        assertEquals(2, result.getMaxUses());
        assertEquals(0, result.getUsedCount());
        assertNotNull(result.getQrImage());
        assertTrue(result.getQrImage().startsWith("data:image/png;base64,"));

        verify(passCodeMapper).insert(any());
    }

    @Test
    void testGenerateForAppointment_AlreadyExists() {
        Appointment appointment = Appointment.builder().id(1L).build();

        PassCode existing = PassCode.builder()
                .id(1L).appointmentId(1L).code("existing.code")
                .status(PassCodeStatusEnum.ACTIVE).build();

        when(passCodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        PassCode result = passCodeService.generateForAppointment(appointment);

        assertEquals(existing, result);
        verify(passCodeMapper, never()).insert(any());
    }

    // ── Phase 1: verifyForScan tests ────────────────────────────────────

    @Test
    void testVerifyForScan_Success() {
        String code = QrCodeUtil.generatePassCode(HMAC_KEY);
        PassCode passCode = PassCode.builder()
                .id(1L).code(code).appointmentId(1L)
                .maxUses(2).usedCount(0)
                .validFrom(LocalDateTime.now().minusHours(1))
                .validTo(LocalDateTime.now().plusHours(3))
                .status(PassCodeStatusEnum.ACTIVE)
                .build();

        when(redisLock.tryLock(anyString())).thenReturn("lock-value");
        when(passCodeMapper.findByCode(code)).thenReturn(passCode);

        PassCode result = passCodeService.verifyForScan(code);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        // Usage count should NOT have been incremented (read-only)
        assertEquals(0, passCode.getUsedCount());
        verify(redisLock).unlock(anyString(), eq("lock-value"));
        // Should NOT have called updateById (no state mutation)
        verify(passCodeMapper, never()).updateById(any());
    }

    @Test
    void testVerifyForScan_InvalidSignature() {
        BizException ex = assertThrows(BizException.class,
                () -> passCodeService.verifyForScan("invalid.code"));
        assertEquals(ErrorCode.PASS_CODE_INVALID, ex.getErrorCode());
    }

    @Test
    void testVerifyForScan_DuplicateScan() {
        String code = QrCodeUtil.generatePassCode(HMAC_KEY);
        when(redisLock.tryLock(anyString())).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> passCodeService.verifyForScan(code));
        assertEquals(ErrorCode.PASS_CODE_DUPLICATE_SCAN, ex.getErrorCode());
    }

    @Test
    void testVerifyForScan_NotYetValid() {
        String code = QrCodeUtil.generatePassCode(HMAC_KEY);
        PassCode passCode = PassCode.builder()
                .id(1L).code(code)
                .maxUses(2).usedCount(0)
                .validFrom(LocalDateTime.now().plusHours(1))
                .validTo(LocalDateTime.now().plusHours(5))
                .status(PassCodeStatusEnum.ACTIVE)
                .build();

        when(redisLock.tryLock(anyString())).thenReturn("lock-value");
        when(passCodeMapper.findByCode(code)).thenReturn(passCode);

        BizException ex = assertThrows(BizException.class,
                () -> passCodeService.verifyForScan(code));
        assertEquals(ErrorCode.PASS_CODE_NOT_YET_VALID, ex.getErrorCode());
    }

    @Test
    void testVerifyForScan_Expired() {
        String code = QrCodeUtil.generatePassCode(HMAC_KEY);
        PassCode passCode = PassCode.builder()
                .id(1L).code(code)
                .maxUses(2).usedCount(0)
                .validFrom(LocalDateTime.now().minusHours(5))
                .validTo(LocalDateTime.now().minusHours(1))
                .status(PassCodeStatusEnum.ACTIVE)
                .build();

        when(redisLock.tryLock(anyString())).thenReturn("lock-value");
        when(passCodeMapper.findByCode(code)).thenReturn(passCode);
        when(redisTemplate.delete(anyString())).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> passCodeService.verifyForScan(code));
        assertEquals(ErrorCode.PASS_CODE_EXPIRED, ex.getErrorCode());
        assertEquals(PassCodeStatusEnum.EXPIRED, passCode.getStatus());
    }

    @Test
    void testVerifyForScan_Revoked() {
        String code = QrCodeUtil.generatePassCode(HMAC_KEY);
        PassCode passCode = PassCode.builder()
                .id(1L).code(code)
                .maxUses(2).usedCount(0)
                .validFrom(LocalDateTime.now().minusHours(1))
                .validTo(LocalDateTime.now().plusHours(3))
                .status(PassCodeStatusEnum.REVOKED)
                .build();

        when(redisLock.tryLock(anyString())).thenReturn("lock-value");
        when(passCodeMapper.findByCode(code)).thenReturn(passCode);

        BizException ex = assertThrows(BizException.class,
                () -> passCodeService.verifyForScan(code));
        assertEquals(ErrorCode.PASS_CODE_REVOKED, ex.getErrorCode());
    }

    @Test
    void testVerifyForScan_UsedUp() {
        String code = QrCodeUtil.generatePassCode(HMAC_KEY);
        PassCode passCode = PassCode.builder()
                .id(1L).code(code)
                .maxUses(2).usedCount(2)
                .validFrom(LocalDateTime.now().minusHours(1))
                .validTo(LocalDateTime.now().plusHours(3))
                .status(PassCodeStatusEnum.ACTIVE)
                .build();

        when(redisLock.tryLock(anyString())).thenReturn("lock-value");
        when(passCodeMapper.findByCode(code)).thenReturn(passCode);
        when(redisTemplate.delete(anyString())).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> passCodeService.verifyForScan(code));
        assertEquals(ErrorCode.PASS_CODE_USED_UP, ex.getErrorCode());
    }

    // ── Phase 2: confirmUsage tests ─────────────────────────────────────

    @Test
    void testConfirmUsage_Success() {
        PassCode passCode = PassCode.builder()
                .id(1L).code("test.code").appointmentId(1L)
                .maxUses(2).usedCount(0)
                .validFrom(LocalDateTime.now().minusHours(1))
                .validTo(LocalDateTime.now().plusHours(3))
                .status(PassCodeStatusEnum.ACTIVE)
                .build();

        when(passCodeMapper.selectById(1L)).thenReturn(passCode);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        PassCode result = passCodeService.confirmUsage(1L);

        assertEquals(1, passCode.getUsedCount());
        assertEquals(PassCodeStatusEnum.ACTIVE, passCode.getStatus());
        verify(passCodeMapper).updateById(passCode);
    }

    @Test
    void testConfirmUsage_IncrementsToMax() {
        PassCode passCode = PassCode.builder()
                .id(1L).code("test.code").appointmentId(1L)
                .maxUses(2).usedCount(1)
                .validFrom(LocalDateTime.now().minusHours(1))
                .validTo(LocalDateTime.now().plusHours(3))
                .status(PassCodeStatusEnum.ACTIVE)
                .build();

        when(passCodeMapper.selectById(1L)).thenReturn(passCode);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        passCodeService.confirmUsage(1L);

        assertEquals(2, passCode.getUsedCount());
        assertEquals(PassCodeStatusEnum.USED_UP, passCode.getStatus());
    }

    @Test
    void testConfirmUsage_AlreadyRevoked() {
        PassCode passCode = PassCode.builder()
                .id(1L).code("test.code")
                .maxUses(2).usedCount(0)
                .validFrom(LocalDateTime.now().minusHours(1))
                .validTo(LocalDateTime.now().plusHours(3))
                .status(PassCodeStatusEnum.REVOKED)
                .build();

        when(passCodeMapper.selectById(1L)).thenReturn(passCode);

        BizException ex = assertThrows(BizException.class,
                () -> passCodeService.confirmUsage(1L));
        assertEquals(ErrorCode.PASS_CODE_REVOKED, ex.getErrorCode());
    }

    @Test
    void testConfirmUsage_ExpiredBetweenPhases() {
        // Simulate: code was valid during verifyForScan, but expired by the time confirmUsage runs
        PassCode passCode = PassCode.builder()
                .id(1L).code("test.code")
                .maxUses(2).usedCount(0)
                .validFrom(LocalDateTime.now().minusHours(5))
                .validTo(LocalDateTime.now().minusMinutes(1)) // just expired
                .status(PassCodeStatusEnum.ACTIVE)
                .build();

        when(passCodeMapper.selectById(1L)).thenReturn(passCode);
        when(redisTemplate.delete(anyString())).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> passCodeService.confirmUsage(1L));
        assertEquals(ErrorCode.PASS_CODE_EXPIRED, ex.getErrorCode());
    }

    // ── Legacy verifyAndUse tests ───────────────────────────────────────

    @Test
    void testVerifyAndUse_Legacy_Success() {
        String code = QrCodeUtil.generatePassCode(HMAC_KEY);
        PassCode passCode = PassCode.builder()
                .id(1L).code(code).appointmentId(1L)
                .maxUses(2).usedCount(0)
                .validFrom(LocalDateTime.now().minusHours(1))
                .validTo(LocalDateTime.now().plusHours(3))
                .status(PassCodeStatusEnum.ACTIVE)
                .build();

        when(redisLock.tryLock(anyString())).thenReturn("lock-value");
        when(passCodeMapper.findByCode(code)).thenReturn(passCode);
        when(passCodeMapper.selectById(1L)).thenReturn(passCode);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        PassCode result = passCodeService.verifyAndUse(code, "ENTRY");

        assertNotNull(result);
        assertEquals(1, passCode.getUsedCount());
    }

    // ── Revoke tests ────────────────────────────────────────────────────

    @Test
    void testRevokeByAppointmentId() {
        PassCode passCode = PassCode.builder()
                .id(1L).appointmentId(5L).code("test.code")
                .status(PassCodeStatusEnum.ACTIVE).build();

        when(passCodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(passCode);
        when(redisTemplate.delete(anyString())).thenReturn(true);

        passCodeService.revokeByAppointmentId(5L);

        assertEquals(PassCodeStatusEnum.REVOKED, passCode.getStatus());
        verify(passCodeMapper).updateById(passCode);
    }

    @Test
    void testRevokeByAppointmentId_AlreadyRevoked() {
        PassCode passCode = PassCode.builder()
                .id(1L).appointmentId(5L).code("test.code")
                .status(PassCodeStatusEnum.REVOKED).build();

        when(passCodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(passCode);

        passCodeService.revokeByAppointmentId(5L);

        // Should be a no-op
        verify(passCodeMapper, never()).updateById(any());
    }
}
