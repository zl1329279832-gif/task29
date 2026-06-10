package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.model.dto.AppointmentCreateRequest;
import com.visitor.model.dto.AppointmentRescheduleRequest;
import com.visitor.model.entity.Appointment;
import com.visitor.model.entity.SysUser;
import com.visitor.model.entity.Visitor;
import com.visitor.model.enums.AppointmentStatusEnum;
import com.visitor.model.enums.RoleEnum;
import com.visitor.model.enums.VisitTypeEnum;
import com.visitor.util.RedisLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    @InjectMocks
    private AppointmentService appointmentService;

    @Mock private AppointmentMapper appointmentMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private VisitorService visitorService;
    @Mock private BlacklistService blacklistService;
    @Mock private PassCodeService passCodeService;
    @Mock private WebSocketPushService webSocketPushService;
    @Mock private RedisLock redisLock;

    private SysUser hostUser;
    private Visitor testVisitor;

    @BeforeEach
    void setUp() {
        hostUser = SysUser.builder()
                .id(1L).username("employee1").realName("Zhang San")
                .deptName("Engineering").role(RoleEnum.EMPLOYEE).status(1)
                .build();

        testVisitor = Visitor.builder()
                .id(10L).name("Li Si").phone("13800138000")
                .company("Test Corp").visitCount(0)
                .build();

        var auth = new UsernamePasswordAuthenticationToken(
                "employee1", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void testCreateAppointment_Success() {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setVisitorId(10L);
        request.setVisitType(VisitTypeEnum.NORMAL);
        request.setPurpose("Business meeting");
        request.setExpectedArrive(LocalDateTime.now().plusHours(1));
        request.setExpectedLeave(LocalDateTime.now().plusHours(3));

        when(sysUserMapper.findByUsername("employee1")).thenReturn(hostUser);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentMapper.countDuplicate(anyLong(), anyLong(), any(), any(), any())).thenReturn(0);
        when(appointmentMapper.insert(any())).thenReturn(1);

        Appointment result = appointmentService.create(request);

        assertNotNull(result);
        assertEquals(AppointmentStatusEnum.PENDING, result.getStatus());
        assertEquals(10L, result.getVisitorId());
        assertEquals(1L, result.getHostId());
        assertTrue(result.getAppointNo().startsWith("APT"));

        verify(blacklistService).assertNotBlacklisted("Li Si", null, "13800138000");
        verify(appointmentMapper).insert(any());
        verify(webSocketPushService).pushAfterCommit(any());
    }

    @Test
    void testCreateAppointment_DuplicateDetected() {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setVisitorId(10L);
        request.setVisitType(VisitTypeEnum.NORMAL);
        request.setExpectedArrive(LocalDateTime.now().plusHours(1));

        when(sysUserMapper.findByUsername("employee1")).thenReturn(hostUser);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentMapper.countDuplicate(anyLong(), anyLong(), any(), any(), any())).thenReturn(1);

        BizException ex = assertThrows(BizException.class, () -> appointmentService.create(request));
        assertEquals(ErrorCode.DUPLICATE_APPOINTMENT, ex.getErrorCode());
    }

    @Test
    void testCreateAppointment_BlacklistedVisitor() {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setVisitorId(10L);
        request.setVisitType(VisitTypeEnum.NORMAL);
        request.setExpectedArrive(LocalDateTime.now().plusHours(1));

        when(sysUserMapper.findByUsername("employee1")).thenReturn(hostUser);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        doThrow(new BizException(ErrorCode.BLACKLIST_HIT, "Bad behavior"))
                .when(blacklistService).assertNotBlacklisted(anyString(), any(), anyString());

        BizException ex = assertThrows(BizException.class, () -> appointmentService.create(request));
        assertEquals(ErrorCode.BLACKLIST_HIT, ex.getErrorCode());
    }

    @Test
    void testApproveAppointment_Success() {
        Appointment appointment = Appointment.builder()
                .id(1L).appointNo("APT20240101").visitorId(10L).hostId(1L)
                .status(AppointmentStatusEnum.PENDING)
                .expectedArrive(LocalDateTime.now().plusHours(1))
                .build();

        when(appointmentMapper.selectById(1L)).thenReturn(appointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(sysUserMapper.selectById(1L)).thenReturn(hostUser);

        appointmentService.approve(1L, 100L, "Approved");

        assertEquals(AppointmentStatusEnum.APPROVED, appointment.getStatus());
        assertEquals(100L, appointment.getApprovedBy());
        verify(blacklistService).assertNotBlacklisted("Li Si", null, "13800138000");
        verify(passCodeService).generateForAppointment(appointment);
        verify(webSocketPushService).pushAfterCommit(any());
    }

    @Test
    void testApproveAppointment_BlacklistRecheckFails() {
        Appointment appointment = Appointment.builder()
                .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                .status(AppointmentStatusEnum.PENDING)
                .expectedArrive(LocalDateTime.now().plusHours(1))
                .build();

        when(appointmentMapper.selectById(1L)).thenReturn(appointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        doThrow(new BizException(ErrorCode.BLACKLIST_HIT, "Added after appointment"))
                .when(blacklistService).assertNotBlacklisted(anyString(), any(), anyString());

        BizException ex = assertThrows(BizException.class,
                () -> appointmentService.approve(1L, 100L, "ok"));
        assertEquals(ErrorCode.BLACKLIST_HIT, ex.getErrorCode());

        // Appointment should still be PENDING, not approved
        assertEquals(AppointmentStatusEnum.PENDING, appointment.getStatus());
        verify(passCodeService, never()).generateForAppointment(any());
    }

    @Test
    void testApproveAppointment_WrongStatus() {
        Appointment appointment = Appointment.builder()
                .id(1L).status(AppointmentStatusEnum.APPROVED)
                .expectedArrive(LocalDateTime.now().plusHours(1))
                .build();

        when(appointmentMapper.selectById(1L)).thenReturn(appointment);

        BizException ex = assertThrows(BizException.class,
                () -> appointmentService.approve(1L, 100L, "ok"));
        assertEquals(ErrorCode.APPOINTMENT_STATUS_INVALID, ex.getErrorCode());
    }

    @Test
    void testApproveAppointment_ExpiredAppointment() {
        Appointment appointment = Appointment.builder()
                .id(1L).status(AppointmentStatusEnum.PENDING)
                .expectedArrive(LocalDateTime.now().minusHours(1))
                .build();

        when(appointmentMapper.selectById(1L)).thenReturn(appointment);

        BizException ex = assertThrows(BizException.class,
                () -> appointmentService.approve(1L, 100L, "ok"));
        assertEquals(ErrorCode.APPOINTMENT_EXPIRED, ex.getErrorCode());
        assertEquals(AppointmentStatusEnum.EXPIRED, appointment.getStatus());
    }

    @Test
    void testRejectAppointment() {
        Appointment appointment = Appointment.builder()
                .id(1L).appointNo("APT001").hostId(1L)
                .status(AppointmentStatusEnum.PENDING)
                .build();

        when(appointmentMapper.selectById(1L)).thenReturn(appointment);
        when(sysUserMapper.selectById(1L)).thenReturn(hostUser);

        appointmentService.reject(1L, 100L, "Not approved");

        assertEquals(AppointmentStatusEnum.REJECTED, appointment.getStatus());
        assertEquals("Not approved", appointment.getRejectReason());
        verify(webSocketPushService).pushAfterCommit(any());
    }

    @Test
    void testCancelAppointment() {
        Appointment appointment = Appointment.builder()
                .id(1L).hostId(1L).status(AppointmentStatusEnum.APPROVED)
                .build();

        when(sysUserMapper.findByUsername("employee1")).thenReturn(hostUser);
        when(appointmentMapper.selectById(1L)).thenReturn(appointment);

        appointmentService.cancel(1L);

        assertEquals(AppointmentStatusEnum.CANCELLED, appointment.getStatus());
        verify(passCodeService).revokeByAppointmentId(1L);
    }

    @Test
    void testCancelAppointment_Unauthorized() {
        Appointment appointment = Appointment.builder()
                .id(1L).hostId(999L).status(AppointmentStatusEnum.APPROVED)
                .build();

        when(sysUserMapper.findByUsername("employee1")).thenReturn(hostUser);
        when(appointmentMapper.selectById(1L)).thenReturn(appointment);

        BizException ex = assertThrows(BizException.class, () -> appointmentService.cancel(1L));
        assertEquals(ErrorCode.DATA_ACCESS_DENIED, ex.getErrorCode());
    }

    @Test
    void testRescheduleAppointment_Success() {
        Appointment oldAppointment = Appointment.builder()
                .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                .visitType(VisitTypeEnum.NORMAL).purpose("Meeting")
                .status(AppointmentStatusEnum.APPROVED)
                .expectedArrive(LocalDateTime.now().plusDays(1))
                .build();

        AppointmentRescheduleRequest request = new AppointmentRescheduleRequest();
        request.setExpectedArrive(LocalDateTime.now().plusDays(3));
        request.setExpectedLeave(LocalDateTime.now().plusDays(3).plusHours(2));

        when(sysUserMapper.findByUsername("employee1")).thenReturn(hostUser);
        when(redisLock.tryLock(anyString(), any())).thenReturn("lock-value");
        when(appointmentMapper.selectById(1L)).thenReturn(oldAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);

        Appointment newAppointment = appointmentService.reschedule(1L, request);

        assertEquals(AppointmentStatusEnum.CANCELLED, oldAppointment.getStatus());
        assertNotNull(newAppointment);
        assertEquals(AppointmentStatusEnum.PENDING, newAppointment.getStatus());
        assertEquals(1L, newAppointment.getRescheduleFrom());
        verify(passCodeService).revokeByAppointmentId(1L);
        verify(blacklistService).assertNotBlacklisted("Li Si", null, "13800138000");
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    @Test
    void testRescheduleAppointment_BlacklistRecheck() {
        Appointment oldAppointment = Appointment.builder()
                .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                .visitType(VisitTypeEnum.NORMAL).purpose("Meeting")
                .status(AppointmentStatusEnum.APPROVED)
                .build();

        AppointmentRescheduleRequest request = new AppointmentRescheduleRequest();
        request.setExpectedArrive(LocalDateTime.now().plusDays(3));

        when(sysUserMapper.findByUsername("employee1")).thenReturn(hostUser);
        when(redisLock.tryLock(anyString(), any())).thenReturn("lock-value");
        when(appointmentMapper.selectById(1L)).thenReturn(oldAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        doThrow(new BizException(ErrorCode.BLACKLIST_HIT, "Blacklisted between appointments"))
                .when(blacklistService).assertNotBlacklisted(anyString(), any(), anyString());

        BizException ex = assertThrows(BizException.class,
                () -> appointmentService.reschedule(1L, request));
        assertEquals(ErrorCode.BLACKLIST_HIT, ex.getErrorCode());

        // Old appointment should NOT be cancelled if blacklist recheck fails
        assertEquals(AppointmentStatusEnum.APPROVED, oldAppointment.getStatus());
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    @Test
    void testRescheduleAppointment_ConcurrentLockFail() {
        AppointmentRescheduleRequest request = new AppointmentRescheduleRequest();
        request.setExpectedArrive(LocalDateTime.now().plusDays(3));

        when(sysUserMapper.findByUsername("employee1")).thenReturn(hostUser);
        when(redisLock.tryLock(anyString(), any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> appointmentService.reschedule(1L, request));
        assertEquals(ErrorCode.APPOINTMENT_STATUS_INVALID, ex.getErrorCode());
    }

    @Test
    void testCancelAllForVisitor() {
        Appointment appt1 = Appointment.builder()
                .id(1L).appointNo("APT001").visitorId(10L)
                .status(AppointmentStatusEnum.PENDING).build();
        Appointment appt2 = Appointment.builder()
                .id(2L).appointNo("APT002").visitorId(10L)
                .status(AppointmentStatusEnum.APPROVED).build();

        when(appointmentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(appt1, appt2));

        int count = appointmentService.cancelAllForVisitor(10L);

        assertEquals(2, count);
        assertEquals(AppointmentStatusEnum.CANCELLED, appt1.getStatus());
        assertEquals(AppointmentStatusEnum.CANCELLED, appt2.getStatus());
        verify(passCodeService).revokeByAppointmentId(1L);
        verify(passCodeService).revokeByAppointmentId(2L);
    }

    @Test
    void testMarkCheckedIn() {
        Appointment appointment = Appointment.builder()
                .id(1L).status(AppointmentStatusEnum.APPROVED).build();
        when(appointmentMapper.selectById(1L)).thenReturn(appointment);

        appointmentService.markCheckedIn(1L);

        assertEquals(AppointmentStatusEnum.CHECKED_IN, appointment.getStatus());
    }

    @Test
    void testMarkCompleted() {
        Appointment appointment = Appointment.builder()
                .id(1L).status(AppointmentStatusEnum.CHECKED_IN).build();
        when(appointmentMapper.selectById(1L)).thenReturn(appointment);

        appointmentService.markCompleted(1L);

        assertEquals(AppointmentStatusEnum.COMPLETED, appointment.getStatus());
    }

    @Test
    void testExpireOverdueAppointments() {
        Appointment expired1 = Appointment.builder()
                .id(1L).status(AppointmentStatusEnum.PENDING)
                .expectedArrive(LocalDateTime.now().minusHours(1)).build();
        Appointment expired2 = Appointment.builder()
                .id(2L).status(AppointmentStatusEnum.PENDING)
                .expectedArrive(LocalDateTime.now().minusHours(2)).build();

        when(appointmentMapper.selectExpiredPending(any())).thenReturn(List.of(expired1, expired2));

        int count = appointmentService.expireOverdueAppointments();

        assertEquals(2, count);
        assertEquals(AppointmentStatusEnum.EXPIRED, expired1.getStatus());
        assertEquals(AppointmentStatusEnum.EXPIRED, expired2.getStatus());
        // Pass codes also revoked on expiry
        verify(passCodeService).revokeByAppointmentId(1L);
        verify(passCodeService).revokeByAppointmentId(2L);
    }
}
