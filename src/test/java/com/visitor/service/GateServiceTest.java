package com.visitor.service;

import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AccessLogMapper;
import com.visitor.mapper.AnomalyRecordMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.model.dto.GateCheckinRequest;
import com.visitor.model.dto.GateCheckoutRequest;
import com.visitor.model.entity.*;
import com.visitor.model.enums.*;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GateServiceTest {

    @InjectMocks
    private GateService gateService;

    @Mock private AccessLogMapper accessLogMapper;
    @Mock private AnomalyRecordMapper anomalyRecordMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private PassCodeService passCodeService;
    @Mock private AppointmentService appointmentService;
    @Mock private VisitorService visitorService;
    @Mock private BlacklistService blacklistService;
    @Mock private WebSocketPushService webSocketPushService;

    private SysUser securityUser;
    private SysUser hostUser;
    private Visitor testVisitor;
    private Appointment testAppointment;
    private PassCode testPassCode;

    @BeforeEach
    void setUp() {
        securityUser = SysUser.builder()
                .id(2L).username("security1").role(RoleEnum.SECURITY).status(1).build();
        hostUser = SysUser.builder()
                .id(1L).username("employee1").realName("Zhang San").role(RoleEnum.EMPLOYEE).status(1).build();
        testVisitor = Visitor.builder()
                .id(10L).name("Li Si").phone("13800138000").build();
        testAppointment = Appointment.builder()
                .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                .status(AppointmentStatusEnum.APPROVED).build();
        testPassCode = PassCode.builder()
                .id(1L).code("valid.code").appointmentId(1L)
                .maxUses(2).usedCount(0).status(PassCodeStatusEnum.ACTIVE)
                .lockKey("visitor:scan:valid.code").lockValue("lock-123")
                .build();

        var auth = new UsernamePasswordAuthenticationToken(
                "security1", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_SECURITY")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void testCheckin_Success() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateLocation("Main Gate");

        when(passCodeService.verify("valid.code")).thenReturn(testPassCode);
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
        when(accessLogMapper.countUndepartedEntry(1L)).thenReturn(0);
        when(accessLogMapper.countExistingPassLog(1L, 1L, "ENTRY")).thenReturn(0);
        when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
        when(sysUserMapper.selectById(1L)).thenReturn(hostUser);
        when(accessLogMapper.insert(any())).thenReturn(1);

        AccessLog result = gateService.checkin(request);

        assertNotNull(result);
        assertEquals(AccessActionEnum.ENTRY, result.getAction());
        assertEquals(AccessResultEnum.PASS, result.getResult());
        assertEquals("Main Gate", result.getGateLocation());

        // verify() then markUsed() — correct order
        verify(passCodeService).verify("valid.code");
        verify(passCodeService).markUsed(testPassCode);
        // Lock should NOT be released separately (markUsed handles it)
        verify(passCodeService, never()).releaseScanLock(any());

        verify(appointmentService).markCheckedIn(1L);
        verify(visitorService).incrementVisitCount(10L);
        verify(webSocketPushService).pushAfterCommit(any());
    }

    @Test
    void testCheckin_BlacklistedVisitor_LockReleasedWithoutConsuming() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateLocation("Main Gate");

        Blacklist blacklist = Blacklist.builder()
                .id(1L).name("Li Si").reason("Previous incident").build();

        when(passCodeService.verify("valid.code")).thenReturn(testPassCode);
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(blacklist);
        when(accessLogMapper.insert(any())).thenReturn(1);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.BLACKLIST_HIT, ex.getErrorCode());

        // Code should NOT be consumed — lock released without markUsed
        verify(passCodeService, never()).markUsed(any());
        verify(passCodeService).releaseScanLock(testPassCode);

        // Blacklist alert and anomaly still recorded
        verify(anomalyRecordMapper).insert(any());
        verify(webSocketPushService).pushAfterCommit(any());
    }

    @Test
    void testCheckin_DuplicateEntry_UndepartedVisitor() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateLocation("Main Gate");

        when(passCodeService.verify("valid.code")).thenReturn(testPassCode);
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
        when(accessLogMapper.countUndepartedEntry(1L)).thenReturn(1); // has undeparted entry

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.DUPLICATE_ENTRY, ex.getErrorCode());

        // Code NOT consumed, lock released
        verify(passCodeService, never()).markUsed(any());
        verify(passCodeService).releaseScanLock(testPassCode);
        // Anomaly recorded
        verify(anomalyRecordMapper).insert(any());
    }

    @Test
    void testCheckin_IdempotentLog_AlreadyCheckedIn() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateLocation("Main Gate");

        when(passCodeService.verify("valid.code")).thenReturn(testPassCode);
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
        when(accessLogMapper.countUndepartedEntry(1L)).thenReturn(0);
        when(accessLogMapper.countExistingPassLog(1L, 1L, "ENTRY")).thenReturn(1); // already logged

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.ALREADY_CHECKED_IN, ex.getErrorCode());

        verify(passCodeService, never()).markUsed(any());
        verify(passCodeService).releaseScanLock(testPassCode);
    }

    @Test
    void testCheckin_AppointmentNotApproved() {
        testAppointment.setStatus(AppointmentStatusEnum.CHECKED_IN);

        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateLocation("Main Gate");

        when(passCodeService.verify("valid.code")).thenReturn(testPassCode);
        when(appointmentService.getById(1L)).thenReturn(testAppointment);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.APPOINTMENT_STATUS_INVALID, ex.getErrorCode());

        // Lock released without consuming
        verify(passCodeService, never()).markUsed(any());
        verify(passCodeService).releaseScanLock(testPassCode);
    }

    @Test
    void testCheckin_EarlyScan_CodeNotConsumed() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateLocation("Main Gate");

        // verify() throws NOT_YET_VALID — code status unchanged
        when(passCodeService.verify("valid.code"))
                .thenThrow(new BizException(ErrorCode.PASS_CODE_NOT_YET_VALID));

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.PASS_CODE_NOT_YET_VALID, ex.getErrorCode());

        // Nothing consumed or logged
        verify(passCodeService, never()).markUsed(any());
        verify(accessLogMapper, never()).insert(any());
    }

    @Test
    void testCheckout_Success() {
        testAppointment.setStatus(AppointmentStatusEnum.CHECKED_IN);

        GateCheckoutRequest request = new GateCheckoutRequest();
        request.setVisitorId(10L);
        request.setAppointmentId(1L);
        request.setGateLocation("Main Gate");

        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
        when(accessLogMapper.insert(any())).thenReturn(1);

        AccessLog result = gateService.checkout(request);

        assertNotNull(result);
        assertEquals(AccessActionEnum.EXIT, result.getAction());
        assertEquals(AccessResultEnum.PASS, result.getResult());
        verify(appointmentService).markCompleted(1L);
    }

    @Test
    void testCheckout_AlreadyDeparted() {
        testAppointment.setStatus(AppointmentStatusEnum.COMPLETED);

        GateCheckoutRequest request = new GateCheckoutRequest();
        request.setVisitorId(10L);
        request.setAppointmentId(1L);

        when(appointmentService.getById(1L)).thenReturn(testAppointment);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkout(request));
        assertEquals(ErrorCode.ALREADY_DEPARTED, ex.getErrorCode());
    }

    @Test
    void testCheckout_NotCheckedIn() {
        testAppointment.setStatus(AppointmentStatusEnum.APPROVED);

        GateCheckoutRequest request = new GateCheckoutRequest();
        request.setVisitorId(10L);
        request.setAppointmentId(1L);

        when(appointmentService.getById(1L)).thenReturn(testAppointment);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkout(request));
        assertEquals(ErrorCode.NOT_CHECKED_IN, ex.getErrorCode());
    }
}
