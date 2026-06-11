package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AccessLogMapper;
import com.visitor.mapper.AnomalyRecordMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.model.dto.GateCheckinRequest;
import com.visitor.model.dto.GateCheckoutRequest;
import com.visitor.model.entity.*;
import com.visitor.model.enums.*;
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
    @Mock private AreaService areaService;
    @Mock private AreaAuthorizationService areaAuthorizationService;
    @Mock private WebSocketPushService webSocketPushService;
    @Mock private RedisLock redisLock;

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

    // ── Checkin success ─────────────────────────────────────────────────

    @Test
    void testCheckin_Success() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateLocation("Main Gate");

        PassCode passCode = PassCode.builder()
                .id(1L).code("valid.code").appointmentId(1L)
                .maxUses(2).usedCount(0).status(PassCodeStatusEnum.ACTIVE).build();

        when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
        when(passCodeService.confirmUsage(1L)).thenReturn(passCode);
        when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
        when(sysUserMapper.selectById(1L)).thenReturn(hostUser);
        when(accessLogMapper.insert(any())).thenReturn(1);

        AccessLog result = gateService.checkin(request);

        assertNotNull(result);
        assertEquals(AccessActionEnum.ENTRY, result.getAction());
        assertEquals(AccessResultEnum.PASS, result.getResult());
        assertEquals("Main Gate", result.getGateLocation());

        verify(passCodeService).confirmUsage(1L);
        verify(appointmentService).markCheckedIn(1L);
        verify(visitorService).incrementVisitCount(10L);
        verify(webSocketPushService).pushVisitorArrived("1", "Li Si", "APT001");
    }

    // ── Checkin: duplicate scan (lock failed) ───────────────────────────

    @Test
    void testCheckin_DuplicateScan() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");

        PassCode passCode = PassCode.builder()
                .id(1L).code("valid.code").appointmentId(1L).build();

        when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.PASS_CODE_DUPLICATE_SCAN, ex.getErrorCode());

        // Pass code should NOT have been consumed
        verify(passCodeService, never()).confirmUsage(anyLong());
    }

    // ── Checkin: idempotent (already CHECKED_IN) ────────────────────────

    @Test
    void testCheckin_Idempotent_AlreadyCheckedIn() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateLocation("Main Gate");

        testAppointment.setStatus(AppointmentStatusEnum.CHECKED_IN);

        PassCode passCode = PassCode.builder()
                .id(1L).code("valid.code").appointmentId(1L).build();

        AccessLog existingLog = AccessLog.builder()
                .id(100L).passCodeId(1L).appointmentId(1L).action(AccessActionEnum.ENTRY)
                .result(AccessResultEnum.PASS).gateLocation("Main Gate").build();

        when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(accessLogMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingLog);

        AccessLog result = gateService.checkin(request);

        assertEquals(100L, result.getId());
        // Should NOT have consumed pass code or created new log
        verify(passCodeService, never()).confirmUsage(anyLong());
        verify(accessLogMapper, never()).insert(any());
    }

    // ── Checkin: blacklist interception (pass code NOT consumed) ─────────

    @Test
    void testCheckin_BlacklistedVisitor_PassCodeNotConsumed() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateLocation("Main Gate");

        PassCode passCode = PassCode.builder()
                .id(1L).code("valid.code").appointmentId(1L).build();

        Blacklist blacklist = Blacklist.builder()
                .id(1L).name("Li Si").reason("Previous incident").build();

        when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(blacklist);
        when(accessLogMapper.insert(any())).thenReturn(1);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.BLACKLIST_HIT, ex.getErrorCode());

        // CRITICAL: pass code should NOT have been consumed
        verify(passCodeService, never()).confirmUsage(anyLong());
        // Anomaly record and denied log should be created
        verify(anomalyRecordMapper).insert(any());
        verify(webSocketPushService).pushBlacklistAlert(eq("Li Si"), eq("Previous incident"), eq("Main Gate"));
    }

    // ── Checkin: undeparted record blocks re-entry ──────────────────────

    @Test
    void testCheckin_UndepartedRecord_BlocksReentry() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateLocation("Main Gate");

        PassCode passCode = PassCode.builder()
                .id(1L).code("valid.code").appointmentId(1L).build();

        when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(true);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.NO_REENTRY_WITHOUT_EXIT, ex.getErrorCode());

        // Pass code should NOT have been consumed
        verify(passCodeService, never()).confirmUsage(anyLong());
        verify(appointmentService, never()).markCheckedIn(anyLong());
    }

    // ── Checkin: wrong appointment status ───────────────────────────────

    @Test
    void testCheckin_WrongAppointmentStatus() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");

        testAppointment.setStatus(AppointmentStatusEnum.CANCELLED);

        PassCode passCode = PassCode.builder()
                .id(1L).code("valid.code").appointmentId(1L).build();

        when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(testAppointment);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.APPOINTMENT_STATUS_INVALID, ex.getErrorCode());

        verify(passCodeService, never()).confirmUsage(anyLong());
    }

    // ── Checkout success ────────────────────────────────────────────────

    @Test
    void testCheckout_Success() {
        testAppointment.setStatus(AppointmentStatusEnum.CHECKED_IN);

        GateCheckoutRequest request = new GateCheckoutRequest();
        request.setVisitorId(10L);
        request.setAppointmentId(1L);
        request.setGateLocation("Main Gate");

        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(accessLogMapper.insert(any())).thenReturn(1);

        AccessLog result = gateService.checkout(request);

        assertNotNull(result);
        assertEquals(AccessActionEnum.EXIT, result.getAction());
        assertEquals(AccessResultEnum.PASS, result.getResult());
        verify(appointmentService).markCompleted(1L);
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    // ── Checkout: idempotent ────────────────────────────────────────────

    @Test
    void testCheckout_Idempotent_AlreadyDeparted() {
        testAppointment.setStatus(AppointmentStatusEnum.COMPLETED);

        GateCheckoutRequest request = new GateCheckoutRequest();
        request.setVisitorId(10L);
        request.setAppointmentId(1L);

        AccessLog existingLog = AccessLog.builder()
                .id(200L).appointmentId(1L).action(AccessActionEnum.EXIT)
                .result(AccessResultEnum.PASS).build();

        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(accessLogMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingLog);

        AccessLog result = gateService.checkout(request);

        assertEquals(200L, result.getId());
        verify(appointmentService, never()).markCompleted(anyLong());
    }

    @Test
    void testCheckout_AlreadyDeparted_NoLog() {
        testAppointment.setStatus(AppointmentStatusEnum.COMPLETED);

        GateCheckoutRequest request = new GateCheckoutRequest();
        request.setVisitorId(10L);
        request.setAppointmentId(1L);

        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(accessLogMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

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

    // ── Multi-gate tests ──────────────────────────────────────────────

    @Test
    void testCheckin_WithGate_AreaAuthorized_Success() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateLocation("A栋正门");
        request.setGateId(1L);

        PassCode passCode = PassCode.builder()
                .id(1L).code("valid.code").appointmentId(1L)
                .maxUses(4).usedCount(0).status(PassCodeStatusEnum.ACTIVE).build();

        Gate gate = Gate.builder()
                .id(1L).name("A栋正门").areaId(1L).locationDesc("A栋办公楼正门")
                .gateType(GateTypeEnum.NORMAL).status(1).build();

        when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
        when(areaService.getGateAndValidate(1L)).thenReturn(gate);
        when(areaService.isGateEntryAllowed(gate)).thenReturn(true);
        doNothing().when(areaAuthorizationService).validateGateAccess(eq(1L), eq(1L), any());
        when(passCodeService.confirmUsage(1L)).thenReturn(passCode);
        when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
        when(sysUserMapper.selectById(1L)).thenReturn(hostUser);
        when(accessLogMapper.insert(any())).thenReturn(1);

        AccessLog result = gateService.checkin(request);

        assertNotNull(result);
        assertEquals(1L, result.getGateId());
        assertEquals(1L, result.getAreaId());
        verify(areaAuthorizationService).validateGateAccess(eq(1L), eq(1L), any());
        verify(webSocketPushService).pushTrajectoryUpdate(eq("Li Si"), eq("A栋正门"), eq("A栋办公楼正门"), eq("ENTRY"));
    }

    @Test
    void testCheckin_WithGate_AreaUnauthorized_Denied() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateId(1L);

        PassCode passCode = PassCode.builder()
                .id(1L).code("valid.code").appointmentId(1L).build();

        Gate gate = Gate.builder()
                .id(1L).name("B栋正门").areaId(2L).locationDesc("B栋")
                .gateType(GateTypeEnum.NORMAL).status(1).build();

        when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
        when(areaService.getGateAndValidate(1L)).thenReturn(gate);
        when(areaService.isGateEntryAllowed(gate)).thenReturn(true);
        doThrow(new BizException(ErrorCode.UNAUTHORIZED_AREA_ACCESS))
                .when(areaAuthorizationService).validateGateAccess(eq(1L), eq(1L), any());
        when(anomalyRecordMapper.insert(any())).thenReturn(1);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.UNAUTHORIZED_AREA_ACCESS, ex.getErrorCode());

        // Pass code should NOT have been consumed
        verify(passCodeService, never()).confirmUsage(anyLong());
        // Anomaly record should be created with gate context
        verify(anomalyRecordMapper).insert(argThat(record ->
                record.getAnomalyType() == AnomalyTypeEnum.UNAUTHORIZED_AREA
                        && record.getGateId() != null));
        verify(webSocketPushService).pushAreaViolationAlert(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testCheckin_WithGate_CompanionExceeded_Denied() {
        testAppointment.setMaxCompanions(2);

        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateId(1L);
        request.setCompanionCount(5);

        PassCode passCode = PassCode.builder()
                .id(1L).code("valid.code").appointmentId(1L).build();

        Gate gate = Gate.builder()
                .id(1L).name("A栋正门").areaId(1L).locationDesc("A栋")
                .gateType(GateTypeEnum.NORMAL).status(1).build();

        when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
        when(areaService.getGateAndValidate(1L)).thenReturn(gate);
        when(areaService.isGateEntryAllowed(gate)).thenReturn(true);
        doNothing().when(areaAuthorizationService).validateGateAccess(eq(1L), eq(1L), any());
        when(anomalyRecordMapper.insert(any())).thenReturn(1);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.COMPANION_LIMIT_EXCEEDED, ex.getErrorCode());

        verify(passCodeService, never()).confirmUsage(anyLong());
        verify(anomalyRecordMapper).insert(argThat(record ->
                record.getAnomalyType() == AnomalyTypeEnum.COMPANION_ANOMALY));
        verify(webSocketPushService).pushCompanionAnomalyAlert(eq("Li Si"), eq(5), eq(2), anyString());
    }

    @Test
    void testCheckin_WithGate_GateDisabled_Denied() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateId(1L);

        PassCode passCode = PassCode.builder()
                .id(1L).code("valid.code").appointmentId(1L).build();

        when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
        when(areaService.getGateAndValidate(1L))
                .thenThrow(new BizException(ErrorCode.GATE_DISABLED));

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.GATE_DISABLED, ex.getErrorCode());
        verify(passCodeService, never()).confirmUsage(anyLong());
    }

    @Test
    void testCheckout_WithGate_AreaContext() {
        testAppointment.setStatus(AppointmentStatusEnum.CHECKED_IN);

        GateCheckoutRequest request = new GateCheckoutRequest();
        request.setVisitorId(10L);
        request.setAppointmentId(1L);
        request.setGateLocation("A栋正门");
        request.setGateId(1L);

        Gate gate = Gate.builder()
                .id(1L).name("A栋正门").areaId(1L).locationDesc("A栋")
                .gateType(GateTypeEnum.NORMAL).status(1).build();

        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(areaService.getGateAndValidate(1L)).thenReturn(gate);
        when(areaService.isGateExitAllowed(gate)).thenReturn(true);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(accessLogMapper.insert(any())).thenReturn(1);

        AccessLog result = gateService.checkout(request);

        assertNotNull(result);
        assertEquals(AccessActionEnum.EXIT, result.getAction());
        assertEquals(1L, result.getGateId());
        assertEquals(1L, result.getAreaId());
        verify(webSocketPushService).pushTrajectoryUpdate(eq("Li Si"), eq("A栋正门"), eq("A栋"), eq("EXIT"));
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    @Test
    void testCheckout_EntryOnlyGate_Fails() {
        testAppointment.setStatus(AppointmentStatusEnum.CHECKED_IN);

        GateCheckoutRequest request = new GateCheckoutRequest();
        request.setVisitorId(10L);
        request.setAppointmentId(1L);
        request.setGateId(1L);

        Gate gate = Gate.builder()
                .id(1L).name("入口闸机").areaId(1L)
                .gateType(GateTypeEnum.ENTRY_ONLY).status(1).build();

        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(areaService.getGateAndValidate(1L)).thenReturn(gate);
        when(areaService.isGateExitAllowed(gate)).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkout(request));
        assertEquals(ErrorCode.GATE_AREA_MISMATCH, ex.getErrorCode());
        verify(appointmentService, never()).markCompleted(anyLong());
    }
}
