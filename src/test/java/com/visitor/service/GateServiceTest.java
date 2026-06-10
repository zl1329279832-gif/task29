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

        PassCode passCode = PassCode.builder()
                .id(1L).code("valid.code").appointmentId(1L)
                .maxUses(2).usedCount(1).status(PassCodeStatusEnum.ACTIVE).build();

        when(passCodeService.verifyAndUse("valid.code", "ENTRY")).thenReturn(passCode);
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
        when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
        when(sysUserMapper.selectById(1L)).thenReturn(hostUser);
        when(accessLogMapper.insert(any())).thenReturn(1);

        AccessLog result = gateService.checkin(request);

        assertNotNull(result);
        assertEquals(AccessActionEnum.ENTRY, result.getAction());
        assertEquals(AccessResultEnum.PASS, result.getResult());
        assertEquals("Main Gate", result.getGateLocation());

        verify(appointmentService).markCheckedIn(1L);
        verify(visitorService).incrementVisitCount(10L);
        verify(webSocketPushService).pushVisitorArrived("1", "Li Si", "APT001");
    }

    @Test
    void testCheckin_BlacklistedVisitor() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateLocation("Main Gate");

        PassCode passCode = PassCode.builder()
                .id(1L).code("valid.code").appointmentId(1L).build();

        Blacklist blacklist = Blacklist.builder()
                .id(1L).name("Li Si").reason("Previous incident").build();

        when(passCodeService.verifyAndUse("valid.code", "ENTRY")).thenReturn(passCode);
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(blacklist);
        when(accessLogMapper.insert(any())).thenReturn(1);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.BLACKLIST_HIT, ex.getErrorCode());

        verify(webSocketPushService).pushBlacklistAlert(eq("Li Si"), eq("Previous incident"), eq("Main Gate"));
        verify(anomalyRecordMapper).insert(any());
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
