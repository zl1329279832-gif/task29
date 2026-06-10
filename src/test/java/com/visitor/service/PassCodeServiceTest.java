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

    @Mock
    private PassCodeMapper passCodeMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private RedisLock redisLock;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private static final String HMAC_KEY = "TestHmacKey2024";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(passCodeService, "hmacKey", HMAC_KEY);
        ReflectionTestUtils.setField(passCodeService, "defaultMaxUses", 2);
        ReflectionTestUtils.setField(passCodeService, "qrSize", 200);
    }

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

    // ========== verify() + markUsed() split tests ==========

    @Test
    void testVerify_Success() {
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
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        PassCode result = passCodeService.verify(code);

        assertNotNull(result);
        // verify() should NOT increment usedCount
        assertEquals(0, result.getUsedCount());
        assertEquals(PassCodeStatusEnum.ACTIVE, result.getStatus());
        // Lock info should be stored on PassCode for later release
        assertNotNull(result.getLockKey());
        assertNotNull(result.getLockValue());
        // Lock should NOT be released yet
        verify(redisLock, never()).unlock(anyString(), anyString());
    }

    @Test
    void testMarkUsed_IncrementsAndReleasesLock() {
        PassCode passCode = PassCode.builder()
                .id(1L).code("test.code").appointmentId(1L)
                .maxUses(2).usedCount(0)
                .status(PassCodeStatusEnum.ACTIVE)
                .build();
        passCode.setLockKey("visitor:scan:test.code");
        passCode.setLockValue("lock-value");

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        passCodeService.markUsed(passCode);

        assertEquals(1, passCode.getUsedCount());
        assertEquals(PassCodeStatusEnum.ACTIVE, passCode.getStatus());
        verify(passCodeMapper).updateById(passCode);
        // Lock should be released after markUsed
        verify(redisLock).unlock("visitor:scan:test.code", "lock-value");
    }

    @Test
    void testMarkUsed_IncrementsToMax_SetsUsedUp() {
        PassCode passCode = PassCode.builder()
                .id(1L).code("test.code").appointmentId(1L)
                .maxUses(2).usedCount(1)
                .status(PassCodeStatusEnum.ACTIVE)
                .build();
        passCode.setLockKey("visitor:scan:test.code");
        passCode.setLockValue("lock-value");

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        passCodeService.markUsed(passCode);

        assertEquals(2, passCode.getUsedCount());
        assertEquals(PassCodeStatusEnum.USED_UP, passCode.getStatus());
    }

    @Test
    void testReleaseScanLock_WithoutConsuming() {
        PassCode passCode = PassCode.builder()
                .id(1L).code("test.code").appointmentId(1L)
                .maxUses(2).usedCount(0)
                .status(PassCodeStatusEnum.ACTIVE)
                .build();
        passCode.setLockKey("visitor:scan:test.code");
        passCode.setLockValue("lock-value");

        passCodeService.releaseScanLock(passCode);

        // Lock released
        verify(redisLock).unlock("visitor:scan:test.code", "lock-value");
        // Lock info cleared
        assertNull(passCode.getLockKey());
        assertNull(passCode.getLockValue());
        // usedCount NOT incremented
        assertEquals(0, passCode.getUsedCount());
        // No DB update
        verify(passCodeMapper, never()).updateById(any());
    }

    // ========== verify() failure scenarios ==========

    @Test
    void testVerify_InvalidSignature() {
        BizException ex = assertThrows(BizException.class,
                () -> passCodeService.verify("invalid.code"));
        assertEquals(ErrorCode.PASS_CODE_INVALID, ex.getErrorCode());
    }

    @Test
    void testVerify_DuplicateScan_LockFailed() {
        String code = QrCodeUtil.generatePassCode(HMAC_KEY);
        when(redisLock.tryLock(anyString())).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> passCodeService.verify(code));
        assertEquals(ErrorCode.PASS_CODE_DUPLICATE_SCAN, ex.getErrorCode());
    }

    @Test
    void testVerify_NotYetValid_EarlyScan() {
        String code = QrCodeUtil.generatePassCode(HMAC_KEY);
        PassCode passCode = PassCode.builder()
                .id(1L).code(code)
                .maxUses(2).usedCount(0)
                .validFrom(LocalDateTime.now().plusHours(2)) // future
                .validTo(LocalDateTime.now().plusHours(5))
                .status(PassCodeStatusEnum.ACTIVE)
                .build();

        when(redisLock.tryLock(anyString())).thenReturn("lock-value");
        when(passCodeMapper.findByCode(code)).thenReturn(passCode);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        BizException ex = assertThrows(BizException.class,
                () -> passCodeService.verify(code));

        assertEquals(ErrorCode.PASS_CODE_NOT_YET_VALID, ex.getErrorCode());
        // Status should NOT change — code is still valid for future use
        assertEquals(PassCodeStatusEnum.ACTIVE, passCode.getStatus());
        verify(passCodeMapper, never()).updateById(any());
        // Lock should be released on failure
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    @Test
    void testVerify_Expired_TrueExpiry() {
        String code = QrCodeUtil.generatePassCode(HMAC_KEY);
        PassCode passCode = PassCode.builder()
                .id(1L).code(code)
                .maxUses(2).usedCount(0)
                .validFrom(LocalDateTime.now().minusHours(5))
                .validTo(LocalDateTime.now().minusHours(1)) // past
                .status(PassCodeStatusEnum.ACTIVE)
                .build();

        when(redisLock.tryLock(anyString())).thenReturn("lock-value");
        when(passCodeMapper.findByCode(code)).thenReturn(passCode);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        BizException ex = assertThrows(BizException.class,
                () -> passCodeService.verify(code));

        assertEquals(ErrorCode.PASS_CODE_EXPIRED, ex.getErrorCode());
        // Status should be marked EXPIRED
        assertEquals(PassCodeStatusEnum.EXPIRED, passCode.getStatus());
        verify(passCodeMapper).updateById(passCode);
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    @Test
    void testVerify_UsedUp() {
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
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        BizException ex = assertThrows(BizException.class,
                () -> passCodeService.verify(code));
        assertEquals(ErrorCode.PASS_CODE_USED_UP, ex.getErrorCode());
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    @Test
    void testVerify_Revoked() {
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
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        BizException ex = assertThrows(BizException.class,
                () -> passCodeService.verify(code));
        assertEquals(ErrorCode.PASS_CODE_REVOKED, ex.getErrorCode());
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    // ========== verifyAndUse() legacy tests ==========

    @Test
    void testVerifyAndUse_Success() {
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
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        PassCode result = passCodeService.verifyAndUse(code, "ENTRY");

        assertNotNull(result);
        assertEquals(1, result.getUsedCount());
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    // ========== revokeByAppointmentId tests ==========

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
}
