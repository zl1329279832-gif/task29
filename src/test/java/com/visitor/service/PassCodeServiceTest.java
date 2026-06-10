package com.visitor.service;

import com.visitor.common.constant.PassCodeStatus;
import com.visitor.common.exception.BusinessException;
import com.visitor.common.util.PassCodeGenerator;
import com.visitor.dto.response.PassCodeResponse;
import com.visitor.entity.Appointment;
import com.visitor.entity.PassCode;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.PassCodeMapper;
import com.visitor.service.impl.PassCodeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PassCodeServiceTest {

    @Mock private PassCodeMapper passCodeMapper;
    @Mock private AppointmentMapper appointmentMapper;
    @Mock private PassCodeGenerator passCodeGenerator;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private BlacklistService blacklistService;

    @InjectMocks
    private PassCodeServiceImpl passCodeService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(passCodeService, "defaultMaxUse", 2);
        ReflectionTestUtils.setField(passCodeService, "earlyEntryMinutes", 30);
    }

    @Test
    void generate_success() {
        Appointment appointment = new Appointment();
        appointment.setId(1L);
        appointment.setVisitStartTime(LocalDateTime.now().plusHours(1));
        appointment.setVisitEndTime(LocalDateTime.now().plusHours(3));

        when(appointmentMapper.findById(1L)).thenReturn(appointment);
        when(passCodeMapper.findActiveByAppointmentId(1L)).thenReturn(null);
        when(passCodeGenerator.generate(1L)).thenReturn("test-code");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        PassCodeResponse result = passCodeService.generate(1L);

        assertNotNull(result);
        assertEquals("test-code", result.getCode());
        assertEquals(PassCodeStatus.ACTIVE.name(), result.getStatus());
        assertEquals(2, result.getMaxUseCount());
        assertEquals(0, result.getUsedCount());
        verify(passCodeMapper).insert(any(PassCode.class));
    }

    @Test
    void generate_revokesExisting() {
        Appointment appointment = new Appointment();
        appointment.setId(1L);
        appointment.setVisitStartTime(LocalDateTime.now().plusHours(1));
        appointment.setVisitEndTime(LocalDateTime.now().plusHours(3));

        PassCode existing = new PassCode();
        existing.setId(99L);
        existing.setCode("old-code");

        when(appointmentMapper.findById(1L)).thenReturn(appointment);
        when(passCodeMapper.findActiveByAppointmentId(1L)).thenReturn(existing);
        when(passCodeGenerator.generate(1L)).thenReturn("new-code");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        passCodeService.generate(1L);

        verify(passCodeMapper).updateStatus(99L, PassCodeStatus.REVOKED.name());
        verify(redisTemplate).delete(contains("old-code"));
    }

    @Test
    void verify_expired_throwsException() {
        PassCode passCode = new PassCode();
        passCode.setId(1L);
        passCode.setCode("test-code");
        passCode.setStatus(PassCodeStatus.ACTIVE.name());
        passCode.setValidFrom(LocalDateTime.now().minusHours(3));
        passCode.setValidUntil(LocalDateTime.now().minusHours(1));
        passCode.setMaxUseCount(2);
        passCode.setUsedCount(0);

        when(passCodeGenerator.verify("test-code")).thenReturn(true);
        when(passCodeMapper.findByCode("test-code")).thenReturn(passCode);
        when(redisTemplate.delete(anyString())).thenReturn(true);

        assertThrows(BusinessException.class, () -> passCodeService.verify("test-code"));
    }

    @Test
    void verify_invalidSignature_throwsException() {
        when(passCodeGenerator.verify("bad-code")).thenReturn(false);
        assertThrows(BusinessException.class, () -> passCodeService.verify("bad-code"));
    }

    @Test
    void verify_usedUp_throwsException() {
        PassCode passCode = new PassCode();
        passCode.setId(1L);
        passCode.setCode("test-code");
        passCode.setStatus(PassCodeStatus.USED.name());

        when(passCodeGenerator.verify("test-code")).thenReturn(true);
        when(passCodeMapper.findByCode("test-code")).thenReturn(passCode);

        assertThrows(BusinessException.class, () -> passCodeService.verify("test-code"));
    }

    @Test
    void revoke_success() {
        PassCode passCode = new PassCode();
        passCode.setId(1L);
        passCode.setCode("test-code");
        passCode.setStatus(PassCodeStatus.ACTIVE.name());

        when(passCodeMapper.findById(1L)).thenReturn(passCode);
        when(redisTemplate.delete(anyString())).thenReturn(true);

        passCodeService.revoke(1L);

        verify(passCodeMapper).updateStatus(1L, PassCodeStatus.REVOKED.name());
    }

    @Test
    void revoke_alreadyUsed_throwsException() {
        PassCode passCode = new PassCode();
        passCode.setId(1L);
        passCode.setStatus(PassCodeStatus.USED.name());

        when(passCodeMapper.findById(1L)).thenReturn(passCode);

        assertThrows(BusinessException.class, () -> passCodeService.revoke(1L));
    }
}
