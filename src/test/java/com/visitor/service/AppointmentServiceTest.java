package com.visitor.service;

import com.visitor.common.constant.AppointmentStatus;
import com.visitor.common.exception.BusinessException;
import com.visitor.common.exception.DuplicateAppointmentException;
import com.visitor.dto.request.AppointmentCreateRequest;
import com.visitor.dto.request.AppointmentRescheduleRequest;
import com.visitor.entity.Appointment;
import com.visitor.entity.SysUser;
import com.visitor.entity.Visitor;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.PassCodeMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.mapper.VisitorMapper;
import com.visitor.security.CustomUserDetails;
import com.visitor.service.impl.AppointmentServiceImpl;
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

    @Mock private AppointmentMapper appointmentMapper;
    @Mock private VisitorMapper visitorMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private PassCodeMapper passCodeMapper;
    @Mock private BlacklistService blacklistService;
    @Mock private NotificationService notificationService;

    @InjectMocks
    private AppointmentServiceImpl appointmentService;

    @BeforeEach
    void setUp() {
        CustomUserDetails userDetails = new CustomUserDetails(
                1L, "testuser", "password", "Test User", "IT",
                List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
    }

    @Test
    void create_success() {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setVisitorName("张三");
        request.setVisitorPhone("13800000001");
        request.setVisitReason("商务洽谈");
        request.setVisitStartTime(LocalDateTime.now().plusHours(1));
        request.setVisitEndTime(LocalDateTime.now().plusHours(3));

        when(blacklistService.isBlacklisted(anyString(), any())).thenReturn(false);
        when(visitorMapper.findByPhone("13800000001")).thenReturn(null);
        when(appointmentMapper.findDuplicate(any(), any(), any(), any())).thenReturn(Collections.emptyList());

        SysUser host = new SysUser();
        host.setId(1L);
        host.setRealName("Test User");
        when(sysUserMapper.findById(1L)).thenReturn(host);

        var result = appointmentService.create(request);

        assertNotNull(result);
        assertEquals("PENDING_APPROVAL", result.getStatus());
        verify(visitorMapper).insert(any(Visitor.class));
        verify(appointmentMapper).insert(any(Appointment.class));
        verify(notificationService).sendApprovalRemind(any());
    }

    @Test
    void create_duplicateAppointment_throwsException() {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setVisitorName("张三");
        request.setVisitorPhone("13800000001");
        request.setVisitReason("商务洽谈");
        request.setVisitStartTime(LocalDateTime.now().plusHours(1));
        request.setVisitEndTime(LocalDateTime.now().plusHours(3));

        Visitor existing = new Visitor();
        existing.setId(1L);
        when(visitorMapper.findByPhone("13800000001")).thenReturn(existing);
        when(blacklistService.isBlacklisted(anyString(), any())).thenReturn(false);

        Appointment dup = new Appointment();
        when(appointmentMapper.findDuplicate(any(), any(), any(), any())).thenReturn(List.of(dup));

        assertThrows(DuplicateAppointmentException.class, () -> appointmentService.create(request));
    }

    @Test
    void create_blacklisted_throwsException() {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setVisitorName("李四");
        request.setVisitorPhone("13800000002");
        request.setVisitReason("访问");
        request.setVisitStartTime(LocalDateTime.now().plusHours(1));
        request.setVisitEndTime(LocalDateTime.now().plusHours(2));

        when(blacklistService.isBlacklisted("13800000002", null)).thenReturn(true);

        assertThrows(BusinessException.class, () -> appointmentService.create(request));
    }

    @Test
    void cancel_success() {
        Appointment appointment = new Appointment();
        appointment.setId(1L);
        appointment.setStatus(AppointmentStatus.APPROVED.name());
        appointment.setCreatedBy(1L);
        appointment.setHostUserId(1L);

        when(appointmentMapper.findById(1L)).thenReturn(appointment);

        appointmentService.cancel(1L);

        verify(appointmentMapper).updateStatus(1L, AppointmentStatus.CANCELLED.name());
        verify(passCodeMapper).expireByAppointmentId(1L);
    }

    @Test
    void cancel_invalidStatus_throwsException() {
        Appointment appointment = new Appointment();
        appointment.setId(1L);
        appointment.setStatus(AppointmentStatus.CHECKED_OUT.name());
        appointment.setCreatedBy(1L);
        appointment.setHostUserId(1L);

        when(appointmentMapper.findById(1L)).thenReturn(appointment);

        assertThrows(IllegalStateException.class, () -> appointmentService.cancel(1L));
    }

    @Test
    void reschedule_success() {
        Appointment original = new Appointment();
        original.setId(1L);
        original.setStatus(AppointmentStatus.APPROVED.name());
        original.setVisitorId(10L);
        original.setHostUserId(1L);
        original.setVisitReason("原事由");
        original.setVisitorCount(1);
        original.setCreatedBy(1L);

        when(appointmentMapper.findById(1L)).thenReturn(original);
        SysUser host = new SysUser();
        host.setId(1L);
        host.setRealName("Host");
        when(sysUserMapper.findById(1L)).thenReturn(host);
        Visitor visitor = new Visitor();
        visitor.setId(10L);
        visitor.setName("张三");
        when(visitorMapper.findById(10L)).thenReturn(visitor);

        AppointmentRescheduleRequest request = new AppointmentRescheduleRequest();
        request.setNewVisitStartTime(LocalDateTime.now().plusDays(1));
        request.setNewVisitEndTime(LocalDateTime.now().plusDays(1).plusHours(2));

        var result = appointmentService.reschedule(1L, request);

        assertNotNull(result);
        assertEquals("PENDING_APPROVAL", result.getStatus());
        verify(appointmentMapper).updateStatus(1L, AppointmentStatus.RESCHEDULED.name());
        verify(passCodeMapper).expireByAppointmentId(1L);
    }

    @Test
    void stateMachine_validTransitions() {
        assertTrue(AppointmentStatus.PENDING_APPROVAL.canTransitionTo(AppointmentStatus.APPROVED));
        assertTrue(AppointmentStatus.PENDING_APPROVAL.canTransitionTo(AppointmentStatus.REJECTED));
        assertTrue(AppointmentStatus.PENDING_APPROVAL.canTransitionTo(AppointmentStatus.CANCELLED));
        assertTrue(AppointmentStatus.APPROVED.canTransitionTo(AppointmentStatus.CHECKED_IN));
        assertTrue(AppointmentStatus.APPROVED.canTransitionTo(AppointmentStatus.EXPIRED));
        assertTrue(AppointmentStatus.CHECKED_IN.canTransitionTo(AppointmentStatus.CHECKED_OUT));
    }

    @Test
    void stateMachine_invalidTransitions() {
        assertFalse(AppointmentStatus.REJECTED.canTransitionTo(AppointmentStatus.APPROVED));
        assertFalse(AppointmentStatus.CANCELLED.canTransitionTo(AppointmentStatus.APPROVED));
        assertFalse(AppointmentStatus.CHECKED_OUT.canTransitionTo(AppointmentStatus.CHECKED_IN));
        assertFalse(AppointmentStatus.EXPIRED.canTransitionTo(AppointmentStatus.APPROVED));
        assertFalse(AppointmentStatus.PENDING_APPROVAL.canTransitionTo(AppointmentStatus.CHECKED_IN));
    }
}
