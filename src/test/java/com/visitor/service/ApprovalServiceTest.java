package com.visitor.service;

import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.ApprovalRecordMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.model.dto.ApprovalRequest;
import com.visitor.model.entity.Appointment;
import com.visitor.model.entity.SysUser;
import com.visitor.model.enums.AppointmentStatusEnum;
import com.visitor.model.enums.ApprovalActionEnum;
import com.visitor.model.enums.RoleEnum;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    @InjectMocks
    private ApprovalService approvalService;

    @Mock private AppointmentService appointmentService;
    @Mock private ApprovalRecordMapper approvalRecordMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private RedisLock redisLock;

    private SysUser adminUser;

    @BeforeEach
    void setUp() {
        adminUser = SysUser.builder()
                .id(100L).username("admin1").realName("Admin")
                .role(RoleEnum.ADMIN).status(1).build();

        var auth = new UsernamePasswordAuthenticationToken(
                "admin1", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void testProcessApproval_Approve_Success() {
        Appointment appointment = Appointment.builder()
                .id(1L).status(AppointmentStatusEnum.PENDING)
                .expectedArrive(LocalDateTime.now().plusHours(1)).build();

        ApprovalRequest request = new ApprovalRequest();
        request.setAction(ApprovalActionEnum.APPROVE);
        request.setRemark("OK");

        when(sysUserMapper.findByUsername("admin1")).thenReturn(adminUser);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(appointment);

        approvalService.processApproval(1L, request);

        verify(appointmentService).approve(eq(1L), eq(100L), eq("OK"));
        verify(approvalRecordMapper).insert(any());
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    @Test
    void testProcessApproval_Reject_Success() {
        Appointment appointment = Appointment.builder()
                .id(1L).status(AppointmentStatusEnum.PENDING).build();

        ApprovalRequest request = new ApprovalRequest();
        request.setAction(ApprovalActionEnum.REJECT);
        request.setRemark("Not suitable");

        when(sysUserMapper.findByUsername("admin1")).thenReturn(adminUser);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(appointment);

        approvalService.processApproval(1L, request);

        verify(appointmentService).reject(eq(1L), eq(100L), eq("Not suitable"));
        verify(approvalRecordMapper).insert(any());
    }

    @Test
    void testProcessApproval_DuplicateApproval_LockFailed() {
        ApprovalRequest request = new ApprovalRequest();
        request.setAction(ApprovalActionEnum.APPROVE);

        when(sysUserMapper.findByUsername("admin1")).thenReturn(adminUser);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> approvalService.processApproval(1L, request));
        assertEquals(ErrorCode.APPROVAL_ALREADY_PROCESSED, ex.getErrorCode());

        // Should NOT have called approve/reject
        verify(appointmentService, never()).approve(anyLong(), anyLong(), anyString());
        verify(appointmentService, never()).reject(anyLong(), anyLong(), anyString());
    }

    @Test
    void testProcessApproval_AppointmentAlreadyProcessed() {
        // Appointment was already approved by another admin
        Appointment appointment = Appointment.builder()
                .id(1L).status(AppointmentStatusEnum.APPROVED).build();

        ApprovalRequest request = new ApprovalRequest();
        request.setAction(ApprovalActionEnum.APPROVE);

        when(sysUserMapper.findByUsername("admin1")).thenReturn(adminUser);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(appointment);

        BizException ex = assertThrows(BizException.class,
                () -> approvalService.processApproval(1L, request));
        assertEquals(ErrorCode.APPROVAL_ALREADY_PROCESSED, ex.getErrorCode());

        verify(appointmentService, never()).approve(anyLong(), anyLong(), anyString());
    }

    @Test
    void testProcessApproval_AppointmentNotFound() {
        ApprovalRequest request = new ApprovalRequest();
        request.setAction(ApprovalActionEnum.APPROVE);

        when(sysUserMapper.findByUsername("admin1")).thenReturn(adminUser);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> approvalService.processApproval(1L, request));
        assertEquals(ErrorCode.APPOINTMENT_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void testProcessApproval_LockReleasedOnException() {
        Appointment appointment = Appointment.builder()
                .id(1L).status(AppointmentStatusEnum.PENDING).build();

        ApprovalRequest request = new ApprovalRequest();
        request.setAction(ApprovalActionEnum.APPROVE);
        request.setRemark("OK");

        when(sysUserMapper.findByUsername("admin1")).thenReturn(adminUser);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(appointment);
        // approve throws → simulating a downstream failure
        doThrow(new BizException(ErrorCode.BLACKLIST_HIT, "blocked"))
                .when(appointmentService).approve(eq(1L), eq(100L), eq("OK"));

        assertThrows(BizException.class,
                () -> approvalService.processApproval(1L, request));

        // Lock must be released even on exception
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }
}
