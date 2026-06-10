package com.visitor.service;

import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AccessLogMapper;
import com.visitor.mapper.AnomalyRecordMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.model.dto.GateCheckinRequest;
import com.visitor.model.dto.GateCheckoutRequest;
import com.visitor.model.dto.GateScanRequest;
import com.visitor.model.entity.*;
import com.visitor.model.enums.*;
import com.visitor.util.RedisLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

/**
 * Tests for the extended GateService with multi-gate, area authorization,
 * and trajectory support.
 */
@ExtendWith(MockitoExtension.class)
class MultiGateGateServiceTest {

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
    @Mock private RedisLock redisLock;
    @Mock private GateManageService gateManageService;
    @Mock private AreaService areaService;
    @Mock private AreaAuthorizationService areaAuthorizationService;
    @Mock private TrajectoryService trajectoryService;

    private SysUser securityUser;
    private SysUser hostUser;
    private Visitor testVisitor;
    private Appointment testAppointment;
    private PassCode testPassCode;
    private Gate testGate;
    private Area testArea;

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
                .maxUses(2).usedCount(0).status(PassCodeStatusEnum.ACTIVE).build();
        testGate = Gate.builder()
                .id(1L).gateCode("GATE-001").gateName("Main Gate").areaId(1L)
                .direction(GateDirectionEnum.BIDIRECTIONAL).status(GateStatusEnum.ACTIVE).build();
        testArea = Area.builder()
                .id(1L).areaCode("AREA-001").areaName("Building A")
                .securityLevel(SecurityLevelEnum.MEDIUM).status(AreaStatusEnum.ACTIVE).build();

        var auth = new UsernamePasswordAuthenticationToken(
                "security1", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_SECURITY")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ── 1. Checkin with gate + area authorized → success, trajectory recorded ──

    @Test
    void testCheckin_WithGate_AreaAuthorized_Success() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateId(1L);
        request.setGateLocation("Main Gate");

        when(passCodeService.verifyForScan("valid.code")).thenReturn(testPassCode);
        when(gateManageService.validateGateActive(1L)).thenReturn(testGate);
        when(areaService.getById(1L)).thenReturn(testArea);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
        when(areaAuthorizationService.checkAuthorization(1L, 1L)).thenReturn(true);
        when(passCodeService.confirmUsage(1L)).thenReturn(testPassCode);
        when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
        when(sysUserMapper.selectById(1L)).thenReturn(hostUser);
        when(accessLogMapper.insert(any())).thenReturn(1);

        AccessLog result = gateService.checkin(request);

        assertNotNull(result);
        assertEquals(AccessActionEnum.ENTRY, result.getAction());
        assertEquals(AccessResultEnum.PASS, result.getResult());
        assertEquals(1L, result.getGateId());
        assertEquals(1L, result.getAreaId());

        verify(passCodeService).confirmUsage(1L);
        verify(appointmentService).markCheckedIn(1L);
        verify(trajectoryService).recordEntry(10L, 1L, 1L, 1L);
        verify(webSocketPushService).pushVisitorArrived("1", "Li Si", "APT001");
    }

    // ── 2. Checkin with gate + area unauthorized → AREA_UNAUTHORIZED ───────

    @Test
    void testCheckin_WithGate_AreaUnauthorized() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateId(1L);
        request.setGateLocation("Main Gate");

        when(passCodeService.verifyForScan("valid.code")).thenReturn(testPassCode);
        when(gateManageService.validateGateActive(1L)).thenReturn(testGate);
        when(areaService.getById(1L)).thenReturn(testArea);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
        when(areaAuthorizationService.checkAuthorization(1L, 1L)).thenReturn(false);
        when(accessLogMapper.insert(any())).thenReturn(1);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.AREA_UNAUTHORIZED, ex.getErrorCode());

        // Pass code should NOT have been consumed
        verify(passCodeService, never()).confirmUsage(anyLong());
        // Anomaly record should be created
        verify(anomalyRecordMapper).insert(any(AnomalyRecord.class));
        // WebSocket push should be sent
        verify(webSocketPushService).pushAreaUnauthorized("Li Si", "Main Gate", "Building A");
        // Trajectory should NOT be recorded
        verify(trajectoryService, never()).recordEntry(anyLong(), anyLong(), anyLong(), anyLong());
    }

    // ── 3. Checkin with inactive gate → GATE_INACTIVE ──────────────────────

    @Test
    void testCheckin_InactiveGate() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateId(1L);

        when(passCodeService.verifyForScan("valid.code")).thenReturn(testPassCode);
        when(gateManageService.validateGateActive(1L))
                .thenThrow(new BizException(ErrorCode.GATE_INACTIVE, "门岗 Main Gate 当前状态: INACTIVE"));

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.GATE_INACTIVE, ex.getErrorCode());

        // Nothing else should have been called
        verify(redisLock, never()).tryLock(anyString(), any(Duration.class));
        verify(passCodeService, never()).confirmUsage(anyLong());
        verify(trajectoryService, never()).recordEntry(anyLong(), anyLong(), anyLong(), anyLong());
    }

    // ── 4. Checkin concurrent scan (lock failed) → PASS_CODE_DUPLICATE_SCAN ─

    @Test
    void testCheckin_ConcurrentScan_LockFailed() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateId(1L);

        when(passCodeService.verifyForScan("valid.code")).thenReturn(testPassCode);
        when(gateManageService.validateGateActive(1L)).thenReturn(testGate);
        when(areaService.getById(1L)).thenReturn(testArea);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.PASS_CODE_DUPLICATE_SCAN, ex.getErrorCode());

        verify(passCodeService, never()).confirmUsage(anyLong());
        verify(appointmentService, never()).markCheckedIn(anyLong());
    }

    // ── 5. Checkin: blacklist hit before area check → BLACKLIST_HIT ────────

    @Test
    void testCheckin_WithGate_BlacklistBeforeAreaCheck() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateId(1L);
        request.setGateLocation("Main Gate");

        Blacklist blacklist = Blacklist.builder()
                .id(1L).name("Li Si").reason("Previous incident").build();

        when(passCodeService.verifyForScan("valid.code")).thenReturn(testPassCode);
        when(gateManageService.validateGateActive(1L)).thenReturn(testGate);
        when(areaService.getById(1L)).thenReturn(testArea);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(blacklist);
        when(accessLogMapper.insert(any())).thenReturn(1);

        BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
        assertEquals(ErrorCode.BLACKLIST_HIT, ex.getErrorCode());

        // Area authorization should NOT have been checked (blacklist is checked first)
        verify(areaAuthorizationService, never()).checkAuthorization(anyLong(), anyLong());
        // Pass code should NOT have been consumed
        verify(passCodeService, never()).confirmUsage(anyLong());
        // Anomaly record should be created for blacklist
        verify(anomalyRecordMapper).insert(any(AnomalyRecord.class));
        verify(webSocketPushService).pushBlacklistAlert(eq("Li Si"), eq("Previous incident"), eq("Main Gate"));
    }

    // ── 6. Checkin without gate → backward compatible, no area check ───────

    @Test
    void testCheckin_WithoutGate_BackwardCompatible() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateLocation("Main Gate");
        // gateId is null

        when(passCodeService.verifyForScan("valid.code")).thenReturn(testPassCode);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
        when(passCodeService.confirmUsage(1L)).thenReturn(testPassCode);
        when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
        when(sysUserMapper.selectById(1L)).thenReturn(hostUser);
        when(accessLogMapper.insert(any())).thenReturn(1);

        AccessLog result = gateService.checkin(request);

        assertNotNull(result);
        assertEquals(AccessActionEnum.ENTRY, result.getAction());
        assertEquals(AccessResultEnum.PASS, result.getResult());
        assertNull(result.getGateId());
        assertNull(result.getAreaId());

        // Gate/area services should NOT have been called
        verify(gateManageService, never()).validateGateActive(anyLong());
        verify(areaService, never()).getById(anyLong());
        verify(areaAuthorizationService, never()).checkAuthorization(anyLong(), anyLong());
        // Trajectory should NOT be recorded when no gate
        verify(trajectoryService, never()).recordEntry(anyLong(), anyLong(), anyLong(), anyLong());
        // Normal flow should still work
        verify(passCodeService).confirmUsage(1L);
        verify(appointmentService).markCheckedIn(1L);
        verify(webSocketPushService).pushVisitorArrived("1", "Li Si", "APT001");
    }

    // ── 7. Checkout with gate → trajectory exit recorded ───────────────────

    @Test
    void testCheckout_WithGate_TrajectoryRecorded() {
        testAppointment.setStatus(AppointmentStatusEnum.CHECKED_IN);

        GateCheckoutRequest request = new GateCheckoutRequest();
        request.setVisitorId(10L);
        request.setAppointmentId(1L);
        request.setGateId(1L);
        request.setGateLocation("Main Gate");

        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(gateManageService.getById(1L)).thenReturn(testGate);
        when(areaService.getById(1L)).thenReturn(testArea);
        when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
        when(accessLogMapper.insert(any())).thenReturn(1);

        AccessLog result = gateService.checkout(request);

        assertNotNull(result);
        assertEquals(AccessActionEnum.EXIT, result.getAction());
        assertEquals(AccessResultEnum.PASS, result.getResult());
        assertEquals(1L, result.getGateId());
        assertEquals(1L, result.getAreaId());

        verify(appointmentService).markCompleted(1L);
        verify(trajectoryService).recordExit(10L, 1L, 1L, 1L);
    }

    // ── 8. PassThrough success → trajectory recorded, pass code NOT consumed ─

    @Test
    void testPassThrough_Success() {
        testAppointment.setStatus(AppointmentStatusEnum.CHECKED_IN);

        GateScanRequest request = new GateScanRequest();
        request.setPassCode("valid.code");
        request.setGateId(1L);
        request.setGateLocation("Main Gate");

        when(passCodeService.verifyForScan("valid.code")).thenReturn(testPassCode);
        when(gateManageService.validateGateActive(1L)).thenReturn(testGate);
        when(areaService.getById(1L)).thenReturn(testArea);
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(areaAuthorizationService.checkAuthorization(1L, 1L)).thenReturn(true);
        when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
        when(accessLogMapper.insert(any())).thenReturn(1);

        AccessLog result = gateService.passThroughGate(request);

        assertNotNull(result);
        assertEquals(AccessActionEnum.ENTRY, result.getAction());
        assertEquals(AccessResultEnum.PASS, result.getResult());
        assertEquals(1L, result.getGateId());
        assertEquals(1L, result.getAreaId());

        // Pass code should NOT be consumed for pass-through
        verify(passCodeService, never()).confirmUsage(anyLong());
        // Trajectory should be recorded
        verify(trajectoryService).recordPassThrough(10L, 1L, 1L, 1L);
    }

    // ── 9. PassThrough area unauthorized → anomaly + denied ────────────────

    @Test
    void testPassThrough_AreaUnauthorized() {
        testAppointment.setStatus(AppointmentStatusEnum.CHECKED_IN);

        GateScanRequest request = new GateScanRequest();
        request.setPassCode("valid.code");
        request.setGateId(1L);
        request.setGateLocation("Main Gate");

        when(passCodeService.verifyForScan("valid.code")).thenReturn(testPassCode);
        when(gateManageService.validateGateActive(1L)).thenReturn(testGate);
        when(areaService.getById(1L)).thenReturn(testArea);
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(areaAuthorizationService.checkAuthorization(1L, 1L)).thenReturn(false);
        when(accessLogMapper.insert(any())).thenReturn(1);

        BizException ex = assertThrows(BizException.class, () -> gateService.passThroughGate(request));
        assertEquals(ErrorCode.AREA_UNAUTHORIZED, ex.getErrorCode());

        // Anomaly should be created
        verify(anomalyRecordMapper).insert(any(AnomalyRecord.class));
        // Push alert should be sent
        verify(webSocketPushService).pushAreaUnauthorized("Li Si", "Main Gate", "Building A");
        // Denied log should be created
        verify(accessLogMapper).insert(any(AccessLog.class));
        // Trajectory should NOT be recorded
        verify(trajectoryService, never()).recordPassThrough(anyLong(), anyLong(), anyLong(), anyLong());
    }

    // ── 10. PassThrough with no gateId → GATE_NOT_FOUND ────────────────────

    @Test
    void testPassThrough_NoGateId() {
        GateScanRequest request = new GateScanRequest();
        request.setPassCode("valid.code");
        // gateId is null

        when(passCodeService.verifyForScan("valid.code")).thenReturn(testPassCode);

        BizException ex = assertThrows(BizException.class, () -> gateService.passThroughGate(request));
        assertEquals(ErrorCode.GATE_NOT_FOUND, ex.getErrorCode());

        verify(gateManageService, never()).validateGateActive(anyLong());
        verify(trajectoryService, never()).recordPassThrough(anyLong(), anyLong(), anyLong(), anyLong());
    }

    // ── 11. PassThrough when not checked in → APPOINTMENT_STATUS_INVALID ───

    @Test
    void testPassThrough_NotCheckedIn() {
        testAppointment.setStatus(AppointmentStatusEnum.APPROVED);

        GateScanRequest request = new GateScanRequest();
        request.setPassCode("valid.code");
        request.setGateId(1L);

        when(passCodeService.verifyForScan("valid.code")).thenReturn(testPassCode);
        when(gateManageService.validateGateActive(1L)).thenReturn(testGate);
        when(areaService.getById(1L)).thenReturn(testArea);
        when(appointmentService.getById(1L)).thenReturn(testAppointment);

        BizException ex = assertThrows(BizException.class, () -> gateService.passThroughGate(request));
        assertEquals(ErrorCode.APPOINTMENT_STATUS_INVALID, ex.getErrorCode());

        verify(areaAuthorizationService, never()).checkAuthorization(anyLong(), anyLong());
        verify(trajectoryService, never()).recordPassThrough(anyLong(), anyLong(), anyLong(), anyLong());
    }

    // ── 12. Checkin area unauthorized → denied log has gateId and areaId ───

    @Test
    void testCheckin_WithGate_AreaAuth_DeniedLogHasGateAndAreaId() {
        GateCheckinRequest request = new GateCheckinRequest();
        request.setPassCode("valid.code");
        request.setGateId(1L);
        request.setGateLocation("Main Gate");

        when(passCodeService.verifyForScan("valid.code")).thenReturn(testPassCode);
        when(gateManageService.validateGateActive(1L)).thenReturn(testGate);
        when(areaService.getById(1L)).thenReturn(testArea);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(appointmentService.getById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
        when(areaAuthorizationService.checkAuthorization(1L, 1L)).thenReturn(false);
        when(accessLogMapper.insert(any())).thenReturn(1);

        assertThrows(BizException.class, () -> gateService.checkin(request));

        // Capture the denied AccessLog to verify gateId and areaId
        ArgumentCaptor<AccessLog> logCaptor = ArgumentCaptor.forClass(AccessLog.class);
        verify(accessLogMapper).insert(logCaptor.capture());

        AccessLog deniedLog = logCaptor.getValue();
        assertEquals(1L, deniedLog.getGateId());
        assertEquals(1L, deniedLog.getAreaId());
        assertEquals(AccessResultEnum.DENIED, deniedLog.getResult());
        assertEquals(AccessActionEnum.ENTRY, deniedLog.getAction());
        assertTrue(deniedLog.getDenyReason().contains("UNAUTHORIZED_AREA"));
        assertTrue(deniedLog.getDenyReason().contains("Building A"));
    }
}
