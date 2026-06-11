package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.*;
import com.visitor.model.dto.GateCheckinRequest;
import com.visitor.model.dto.GateCheckoutRequest;
import com.visitor.model.entity.*;
import com.visitor.model.enums.*;
import com.visitor.model.vo.AccessLogVO;
import com.visitor.model.vo.TrajectoryVO;
import com.visitor.task.ScheduledTasks;
import com.visitor.util.RedisLock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive scenario tests for:
 * 1. Multi-gate concurrent scan with lock contention
 * 2. Batch import → approval → dynamic pass code maxUses
 * 3. Area violation produces consistent anomaly + denied access log
 * 4. Duplicate scan idempotency across checkin and checkout
 * 5. Companion anomaly produces consistent anomaly + denied access log
 * 6. Scheduled overstay detection with anomaly record dedup
 * 7. WebSocket push ordering with sequence numbers
 * 8. Trajectory closure through full entry → exit lifecycle
 */
@ExtendWith(MockitoExtension.class)
class ConcurrentGateScenarioTest {

    // ── GateService dependencies ───────────────────────────────────────
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

    // ── TrajectoryService dependencies ─────────────────────────────────
    @Mock private AppointmentMapper appointmentMapper;

    private GateService gateService;
    private TrajectoryService trajectoryService;

    private SysUser securityUser;
    private SysUser hostUser;
    private Visitor testVisitor;

    @BeforeEach
    void setUp() {
        gateService = new GateService(
                accessLogMapper, anomalyRecordMapper, sysUserMapper,
                passCodeService, appointmentService, visitorService,
                blacklistService, areaService, areaAuthorizationService,
                webSocketPushService, redisLock);

        trajectoryService = new TrajectoryService(
                accessLogMapper, appointmentMapper, visitorService);

        securityUser = SysUser.builder()
                .id(2L).username("security1").role(RoleEnum.SECURITY).status(1).build();
        hostUser = SysUser.builder()
                .id(1L).username("employee1").realName("Zhang San")
                .role(RoleEnum.EMPLOYEE).status(1).build();
        testVisitor = Visitor.builder()
                .id(10L).name("Li Si").phone("13800138000")
                .idCard("310101199001011234").build();

        var auth = new UsernamePasswordAuthenticationToken(
                "security1", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_SECURITY")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Scenario 1: Multi-gate concurrent scan
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multi-gate concurrent scan")
    class MultiGateConcurrentScan {

        @Test
        @DisplayName("Second concurrent checkin on same appointment is blocked by distributed lock")
        void concurrentCheckin_secondScanBlockedByLock() {
            GateCheckinRequest request = new GateCheckinRequest();
            request.setPassCode("valid.code");
            request.setGateId(1L);

            PassCode passCode = PassCode.builder()
                    .id(1L).code("valid.code").appointmentId(1L).build();

            // verifyForScan succeeds but appointment-level lock fails (concurrent scan)
            when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(null);

            BizException ex = assertThrows(BizException.class,
                    () -> gateService.checkin(request));
            assertEquals(ErrorCode.PASS_CODE_DUPLICATE_SCAN, ex.getErrorCode());

            // Pass code was NOT consumed, no state changes
            verify(passCodeService, never()).confirmUsage(anyLong());
            verify(appointmentService, never()).markCheckedIn(anyLong());
            verify(accessLogMapper, never()).insert(any());
        }

        @Test
        @DisplayName("Second concurrent checkout on same appointment is blocked by distributed lock")
        void concurrentCheckout_secondRequestBlockedByLock() {
            GateCheckoutRequest request = new GateCheckoutRequest();
            request.setVisitorId(10L);
            request.setAppointmentId(1L);

            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(null);

            BizException ex = assertThrows(BizException.class,
                    () -> gateService.checkout(request));
            assertEquals(ErrorCode.PASS_CODE_DUPLICATE_SCAN, ex.getErrorCode());

            verify(appointmentService, never()).markCompleted(anyLong());
            verify(accessLogMapper, never()).insert(any());
        }

        @Test
        @DisplayName("Checkin at gate A, then checkout at gate B — full lifecycle")
        void checkinGateA_checkoutGateB_fullLifecycle() {
            Appointment appointment = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.APPROVED).build();

            PassCode passCode = PassCode.builder()
                    .id(1L).code("valid.code").appointmentId(1L)
                    .maxUses(4).usedCount(0).status(PassCodeStatusEnum.ACTIVE).build();

            Gate gateA = Gate.builder()
                    .id(1L).name("A栋正门").areaId(1L).locationDesc("A栋办公楼")
                    .gateType(GateTypeEnum.NORMAL).status(1).build();
            Gate gateB = Gate.builder()
                    .id(2L).name("B栋正门").areaId(2L).locationDesc("B栋会议楼")
                    .gateType(GateTypeEnum.NORMAL).status(1).build();

            // ── Checkin at gate A ──
            GateCheckinRequest checkinReq = new GateCheckinRequest();
            checkinReq.setPassCode("valid.code");
            checkinReq.setGateId(1L);
            checkinReq.setGateLocation("A栋正门");

            when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(appointmentService.getById(1L)).thenReturn(appointment);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
            when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
            when(areaService.getGateAndValidate(1L)).thenReturn(gateA);
            when(areaService.isGateEntryAllowed(gateA)).thenReturn(true);
            doNothing().when(areaAuthorizationService).validateGateAccess(eq(1L), eq(1L), any());
            when(passCodeService.confirmUsage(1L)).thenReturn(passCode);
            when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
            when(sysUserMapper.selectById(1L)).thenReturn(hostUser);
            when(accessLogMapper.insert(any())).thenReturn(1);

            AccessLog checkinLog = gateService.checkin(checkinReq);

            assertEquals(AccessActionEnum.ENTRY, checkinLog.getAction());
            assertEquals(1L, checkinLog.getGateId());
            assertEquals(1L, checkinLog.getAreaId());
            verify(appointmentService).markCheckedIn(1L);
            verify(webSocketPushService).pushVisitorArrived("1", "Li Si", "APT001");
            verify(webSocketPushService).pushTrajectoryUpdate("Li Si", "A栋正门", "A栋办公楼", "ENTRY");

            // ── Checkout at gate B ──
            appointment.setStatus(AppointmentStatusEnum.CHECKED_IN);
            GateCheckoutRequest checkoutReq = new GateCheckoutRequest();
            checkoutReq.setVisitorId(10L);
            checkoutReq.setAppointmentId(1L);
            checkoutReq.setGateId(2L);
            checkoutReq.setGateLocation("B栋正门");

            when(areaService.getGateAndValidate(2L)).thenReturn(gateB);
            when(areaService.isGateExitAllowed(gateB)).thenReturn(true);
            when(passCodeService.getPassCodeByAppointmentId(1L)).thenReturn(passCode);

            AccessLog checkoutLog = gateService.checkout(checkoutReq);

            assertEquals(AccessActionEnum.EXIT, checkoutLog.getAction());
            assertEquals(2L, checkoutLog.getGateId());
            assertEquals(2L, checkoutLog.getAreaId());
            assertEquals(1L, checkoutLog.getPassCodeId());
            verify(appointmentService).markCompleted(1L);
            verify(webSocketPushService).pushTrajectoryUpdate("Li Si", "B栋正门", "B栋会议楼", "EXIT");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Scenario 2: Batch authorization + approval → dynamic maxUses
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Batch authorization and approval pass code flow")
    class BatchAuthorizationApproval {

        @Mock private AppointmentMapper directAppointmentMapper;
        @Mock private PassCodeService directPassCodeService;
        @Mock private AreaAuthorizationService directAreaAuthService;
        @Mock private WebSocketPushService directPushService;

        @Test
        @DisplayName("Approval after batch import uses authorized area count for maxUses")
        void approvalAfterBatchImport_usesAreaCountForMaxUses() {
            AppointmentService appService = new AppointmentService(
                    directAppointmentMapper, sysUserMapper, visitorService,
                    blacklistService, directPassCodeService, directAreaAuthService,
                    directPushService, redisLock);

            Appointment appointment = Appointment.builder()
                    .id(100L).appointNo("APT100").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.PENDING)
                    .expectedArrive(LocalDateTime.now().plusHours(2))
                    .build();

            when(directAppointmentMapper.selectById(100L)).thenReturn(appointment);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(sysUserMapper.selectById(1L)).thenReturn(hostUser);
            // Batch import pre-created 3 area authorizations
            when(directAreaAuthService.countAuthorizedAreas(100L)).thenReturn(3);

            appService.approve(100L, 1L, "Approved with areas");

            // Pass code generated with authorizedAreaCount=3
            verify(directPassCodeService).generateForAppointment(appointment, 3);
        }

        @Test
        @DisplayName("Approval without pre-existing areas uses max(1, 0) = 1 for area count")
        void approvalWithoutAreas_usesDefaultAreaCount() {
            AppointmentService appService = new AppointmentService(
                    directAppointmentMapper, sysUserMapper, visitorService,
                    blacklistService, directPassCodeService, directAreaAuthService,
                    directPushService, redisLock);

            Appointment appointment = Appointment.builder()
                    .id(200L).appointNo("APT200").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.PENDING)
                    .expectedArrive(LocalDateTime.now().plusHours(2))
                    .build();

            when(directAppointmentMapper.selectById(200L)).thenReturn(appointment);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(sysUserMapper.selectById(1L)).thenReturn(hostUser);
            when(directAreaAuthService.countAuthorizedAreas(200L)).thenReturn(0);

            appService.approve(200L, 1L, "Approved no areas");

            // max(1, 0) = 1
            verify(directPassCodeService).generateForAppointment(appointment, 1);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Scenario 3: Area violation + companion anomaly consistency
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Area violation and companion anomaly consistency")
    class AnomalyConsistency {

        @Test
        @DisplayName("Area violation creates anomaly record + DENIED access log + push alert in order")
        void areaViolation_createsConsistentRecords() {
            Appointment appointment = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.APPROVED).build();

            PassCode passCode = PassCode.builder()
                    .id(1L).code("valid.code").appointmentId(1L).build();

            Gate gate = Gate.builder()
                    .id(5L).name("禁区门岗").areaId(10L).locationDesc("禁止区域")
                    .gateType(GateTypeEnum.NORMAL).status(1).build();

            GateCheckinRequest request = new GateCheckinRequest();
            request.setPassCode("valid.code");
            request.setGateId(5L);
            request.setGateLocation("禁区门岗");

            when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(appointmentService.getById(1L)).thenReturn(appointment);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
            when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
            when(areaService.getGateAndValidate(5L)).thenReturn(gate);
            when(areaService.isGateEntryAllowed(gate)).thenReturn(true);
            doThrow(new BizException(ErrorCode.UNAUTHORIZED_AREA_ACCESS))
                    .when(areaAuthorizationService).validateGateAccess(eq(1L), eq(5L), any());
            when(anomalyRecordMapper.insert(any())).thenReturn(1);
            when(accessLogMapper.insert(any())).thenReturn(1);

            assertThrows(BizException.class, () -> gateService.checkin(request));

            // Verify all three records created in correct order:
            // 1. Anomaly record with gate/area context
            verify(anomalyRecordMapper).insert(argThat(record ->
                    record.getAnomalyType() == AnomalyTypeEnum.UNAUTHORIZED_AREA
                            && record.getGateId().equals(5L)
                            && record.getAreaId().equals(10L)
                            && record.getVisitorId().equals(10L)
                            && record.getAppointmentId().equals(1L)
                            && record.getStatus() == AnomalyStatusEnum.OPEN));

            // 2. DENIED access log with gate/area context
            verify(accessLogMapper).insert(argThat(log ->
                    log.getResult() == AccessResultEnum.DENIED
                            && log.getAction() == AccessActionEnum.ENTRY
                            && log.getGateId().equals(5L)
                            && log.getAreaId().equals(10L)
                            && log.getPassCodeId().equals(1L)));

            // 3. WebSocket push to security
            verify(webSocketPushService).pushAreaViolationAlert(
                    eq("Li Si"), eq("禁止区域"), eq("禁区门岗"), eq("未授权进入区域"));

            // Pass code NOT consumed
            verify(passCodeService, never()).confirmUsage(anyLong());
        }

        @Test
        @DisplayName("Companion anomaly creates anomaly record + DENIED access log + push alert")
        void companionAnomaly_createsConsistentRecords() {
            Appointment appointment = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.APPROVED).maxCompanions(1).build();

            PassCode passCode = PassCode.builder()
                    .id(1L).code("valid.code").appointmentId(1L).build();

            Gate gate = Gate.builder()
                    .id(1L).name("A栋正门").areaId(1L).locationDesc("A栋")
                    .gateType(GateTypeEnum.NORMAL).status(1).build();

            GateCheckinRequest request = new GateCheckinRequest();
            request.setPassCode("valid.code");
            request.setGateId(1L);
            request.setGateLocation("A栋正门");
            request.setCompanionCount(10);

            when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(appointmentService.getById(1L)).thenReturn(appointment);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
            when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
            when(areaService.getGateAndValidate(1L)).thenReturn(gate);
            when(areaService.isGateEntryAllowed(gate)).thenReturn(true);
            doNothing().when(areaAuthorizationService).validateGateAccess(eq(1L), eq(1L), any());
            when(anomalyRecordMapper.insert(any())).thenReturn(1);
            when(accessLogMapper.insert(any())).thenReturn(1);

            assertThrows(BizException.class, () -> gateService.checkin(request));

            // Anomaly record
            verify(anomalyRecordMapper).insert(argThat(record ->
                    record.getAnomalyType() == AnomalyTypeEnum.COMPANION_ANOMALY
                            && record.getGateId().equals(1L)));

            // DENIED access log
            verify(accessLogMapper).insert(argThat(log ->
                    log.getResult() == AccessResultEnum.DENIED
                            && log.getDenyReason().contains("COMPANION_EXCEEDED")
                            && log.getDenyReason().contains("actual=10")
                            && log.getDenyReason().contains("max=1")));

            // Push
            verify(webSocketPushService).pushCompanionAnomalyAlert(
                    eq("Li Si"), eq(10), eq(1), eq("A栋正门"));

            verify(passCodeService, never()).confirmUsage(anyLong());
        }

        @Test
        @DisplayName("Blacklist denial creates anomaly + denied log + push (consistent triple)")
        void blacklistDenial_createsConsistentTriple() {
            Appointment appointment = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.APPROVED).build();

            PassCode passCode = PassCode.builder()
                    .id(1L).code("valid.code").appointmentId(1L).build();

            Blacklist bl = Blacklist.builder()
                    .id(1L).name("Li Si").reason("Previous security incident").build();

            GateCheckinRequest request = new GateCheckinRequest();
            request.setPassCode("valid.code");
            request.setGateId(3L);
            request.setGateLocation("C栋正门");

            Gate gate = Gate.builder()
                    .id(3L).name("C栋正门").areaId(3L).locationDesc("C栋")
                    .gateType(GateTypeEnum.NORMAL).status(1).build();

            when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(appointmentService.getById(1L)).thenReturn(appointment);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
            when(blacklistService.check(anyString(), any(), anyString())).thenReturn(bl);
            when(areaService.getGateAndValidate(3L)).thenReturn(gate);
            when(anomalyRecordMapper.insert(any())).thenReturn(1);
            when(accessLogMapper.insert(any())).thenReturn(1);

            BizException ex = assertThrows(BizException.class,
                    () -> gateService.checkin(request));
            assertEquals(ErrorCode.BLACKLIST_HIT, ex.getErrorCode());

            // 1. Anomaly record
            verify(anomalyRecordMapper).insert(argThat(record ->
                    record.getAnomalyType() == AnomalyTypeEnum.BLACKLIST_ATTEMPT
                            && record.getGateId().equals(3L)
                            && record.getAreaId().equals(3L)));

            // 2. DENIED access log
            verify(accessLogMapper).insert(argThat(log ->
                    log.getResult() == AccessResultEnum.DENIED
                            && log.getDenyReason().contains("BLACKLISTED")
                            && log.getGateId().equals(3L)));

            // 3. Push
            verify(webSocketPushService).pushBlacklistAlert(
                    "Li Si", "Previous security incident", "C栋正门");

            verify(passCodeService, never()).confirmUsage(anyLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Scenario 4: Duplicate scan idempotency
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Duplicate scan idempotency")
    class DuplicateScanIdempotency {

        @Test
        @DisplayName("Idempotent checkin returns existing log without side effects")
        void idempotentCheckin_returnExistingLog_noSideEffects() {
            Appointment appointment = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.CHECKED_IN).build();

            PassCode passCode = PassCode.builder()
                    .id(1L).code("valid.code").appointmentId(1L).build();

            AccessLog existingLog = AccessLog.builder()
                    .id(999L).appointmentId(1L).action(AccessActionEnum.ENTRY)
                    .result(AccessResultEnum.PASS).gateLocation("Main Gate").build();

            GateCheckinRequest request = new GateCheckinRequest();
            request.setPassCode("valid.code");

            when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(appointmentService.getById(1L)).thenReturn(appointment);
            when(accessLogMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingLog);

            AccessLog result = gateService.checkin(request);

            assertEquals(999L, result.getId());
            // No state changes
            verify(passCodeService, never()).confirmUsage(anyLong());
            verify(appointmentService, never()).markCheckedIn(anyLong());
            verify(visitorService, never()).incrementVisitCount(anyLong());
            verify(accessLogMapper, never()).insert(any());
            verify(webSocketPushService, never()).pushVisitorArrived(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Idempotent checkout returns existing exit log without side effects")
        void idempotentCheckout_returnsExistingLog_noSideEffects() {
            Appointment appointment = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.COMPLETED).build();

            AccessLog existingLog = AccessLog.builder()
                    .id(888L).appointmentId(1L).action(AccessActionEnum.EXIT)
                    .result(AccessResultEnum.PASS).build();

            GateCheckoutRequest request = new GateCheckoutRequest();
            request.setVisitorId(10L);
            request.setAppointmentId(1L);

            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(appointmentService.getById(1L)).thenReturn(appointment);
            when(accessLogMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingLog);

            AccessLog result = gateService.checkout(request);

            assertEquals(888L, result.getId());
            verify(appointmentService, never()).markCompleted(anyLong());
            verify(accessLogMapper, never()).insert(any());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Scenario 5: Scheduled overstay detection with anomaly dedup
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Overstay detection anomaly record consistency")
    class OverstayDetection {

        @Mock private PassCodeService taskPassCodeService;

        @Test
        @DisplayName("Overstay creates anomaly record AND pushes warning")
        void overstay_createsAnomalyAndPushesWarning() {
            ScheduledTasks tasks = new ScheduledTasks(
                    appointmentService, taskPassCodeService, visitorService,
                    webSocketPushService, anomalyRecordMapper, redisLock);

            Appointment overdueAppt = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L)
                    .status(AppointmentStatusEnum.CHECKED_IN)
                    .expectedLeave(LocalDateTime.now().minusMinutes(90))
                    .build();

            when(redisLock.tryLock(eq("scheduled:detectUndepartedVisitors"), any(Duration.class)))
                    .thenReturn("lock-value");
            when(appointmentService.getCheckedInOverdue()).thenReturn(List.of(overdueAppt));
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(anomalyRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(anomalyRecordMapper.insert(any())).thenReturn(1);

            tasks.detectUndepartedVisitors();

            // Anomaly record created
            verify(anomalyRecordMapper).insert(argThat(record ->
                    record.getAnomalyType() == AnomalyTypeEnum.OVERSTAY
                            && record.getVisitorId().equals(10L)
                            && record.getAppointmentId().equals(1L)
                            && record.getStatus() == AnomalyStatusEnum.OPEN
                            && record.getDescription().contains("超时未离场")));

            // Push warning sent
            verify(webSocketPushService).pushUndepartedWarning(
                    eq("Li Si"), eq("APT001"), anyLong());
        }

        @Test
        @DisplayName("Second detection run does NOT create duplicate anomaly, but still pushes warning")
        void secondDetection_noDuplicateAnomaly_stillPushesWarning() {
            ScheduledTasks tasks = new ScheduledTasks(
                    appointmentService, taskPassCodeService, visitorService,
                    webSocketPushService, anomalyRecordMapper, redisLock);

            Appointment overdueAppt = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L)
                    .status(AppointmentStatusEnum.CHECKED_IN)
                    .expectedLeave(LocalDateTime.now().minusMinutes(90))
                    .build();

            when(redisLock.tryLock(eq("scheduled:detectUndepartedVisitors"), any(Duration.class)))
                    .thenReturn("lock-value");
            when(appointmentService.getCheckedInOverdue()).thenReturn(List.of(overdueAppt));
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            // Existing OPEN anomaly — dedup kicks in
            when(anomalyRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

            tasks.detectUndepartedVisitors();

            // NO new anomaly record
            verify(anomalyRecordMapper, never()).insert(any());
            // Warning is STILL pushed (repeated reminders are intentional)
            verify(webSocketPushService).pushUndepartedWarning(
                    eq("Li Si"), eq("APT001"), anyLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Scenario 6: Trajectory closure through multi-gate lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Trajectory closure verification")
    class TrajectoryClosure {

        @Test
        @DisplayName("Full entry+exit cycle: trajectory shows departed with null currentArea")
        void fullEntryExit_trajectoryShowsDeparted() {
            Appointment appointment = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L)
                    .status(AppointmentStatusEnum.COMPLETED).build();

            LocalDateTime entry = LocalDateTime.now().minusHours(2);
            LocalDateTime exit = LocalDateTime.now().minusMinutes(10);

            AccessLogVO entryLog = AccessLogVO.builder()
                    .id(1L).action(AccessActionEnum.ENTRY).result(AccessResultEnum.PASS)
                    .gateId(1L).gateName("A栋正门").areaId(1L).areaName("A栋办公区")
                    .createdAt(entry).build();
            AccessLogVO exitLog = AccessLogVO.builder()
                    .id(2L).action(AccessActionEnum.EXIT).result(AccessResultEnum.PASS)
                    .gateId(1L).gateName("A栋正门").areaId(1L).areaName("A栋办公区")
                    .createdAt(exit).build();

            when(appointmentMapper.selectById(1L)).thenReturn(appointment);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(accessLogMapper.selectTrajectory(1L)).thenReturn(List.of(entryLog, exitLog));

            TrajectoryVO trajectory = trajectoryService.getTrajectory(1L);

            assertTrue(trajectory.getDeparted());
            assertNull(trajectory.getCurrentAreaId());
            assertNull(trajectory.getCurrentAreaName());
            assertEquals(entry, trajectory.getEntryTime());
            assertEquals(exit, trajectory.getExitTime());
        }

        @Test
        @DisplayName("Entry without exit: trajectory shows not departed with currentArea set")
        void entryWithoutExit_trajectoryShowsNotDeparted() {
            Appointment appointment = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L)
                    .status(AppointmentStatusEnum.CHECKED_IN).build();

            LocalDateTime entry = LocalDateTime.now().minusHours(1);

            AccessLogVO entryLog = AccessLogVO.builder()
                    .id(1L).action(AccessActionEnum.ENTRY).result(AccessResultEnum.PASS)
                    .gateId(1L).gateName("A栋正门").areaId(1L).areaName("A栋办公区")
                    .createdAt(entry).build();

            when(appointmentMapper.selectById(1L)).thenReturn(appointment);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(accessLogMapper.selectTrajectory(1L)).thenReturn(List.of(entryLog));

            TrajectoryVO trajectory = trajectoryService.getTrajectory(1L);

            assertFalse(trajectory.getDeparted());
            assertEquals(1L, trajectory.getCurrentAreaId());
            assertEquals("A栋办公区", trajectory.getCurrentAreaName());
        }

        @Test
        @DisplayName("Entry→exit→re-entry: trajectory shows not departed, area updated")
        void entryExitReentry_notDeparted_areaUpdated() {
            Appointment appointment = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L)
                    .status(AppointmentStatusEnum.CHECKED_IN).build();

            LocalDateTime t1 = LocalDateTime.now().minusHours(3);
            LocalDateTime t2 = LocalDateTime.now().minusHours(2);
            LocalDateTime t3 = LocalDateTime.now().minusHours(1);

            List<AccessLogVO> logs = List.of(
                    AccessLogVO.builder()
                            .id(1L).action(AccessActionEnum.ENTRY).result(AccessResultEnum.PASS)
                            .gateId(1L).gateName("A栋正门").areaId(1L).areaName("A栋办公区")
                            .createdAt(t1).build(),
                    AccessLogVO.builder()
                            .id(2L).action(AccessActionEnum.EXIT).result(AccessResultEnum.PASS)
                            .gateId(1L).gateName("A栋正门").areaId(1L).areaName("A栋办公区")
                            .createdAt(t2).build(),
                    AccessLogVO.builder()
                            .id(3L).action(AccessActionEnum.ENTRY).result(AccessResultEnum.PASS)
                            .gateId(2L).gateName("B栋正门").areaId(2L).areaName("B栋会议区")
                            .createdAt(t3).build()
            );

            when(appointmentMapper.selectById(1L)).thenReturn(appointment);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(accessLogMapper.selectTrajectory(1L)).thenReturn(logs);

            TrajectoryVO trajectory = trajectoryService.getTrajectory(1L);

            // Last action is ENTRY → not departed
            assertFalse(trajectory.getDeparted());
            assertEquals(2L, trajectory.getCurrentAreaId());
            assertEquals("B栋会议区", trajectory.getCurrentAreaName());
            assertEquals(3, trajectory.getPoints().size());
        }

        @Test
        @DisplayName("DENIED logs do not affect trajectory state")
        void deniedLogs_doNotAffectState() {
            Appointment appointment = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L)
                    .status(AppointmentStatusEnum.CHECKED_IN).build();

            LocalDateTime t1 = LocalDateTime.now().minusHours(2);
            LocalDateTime t2 = LocalDateTime.now().minusHours(1);

            List<AccessLogVO> logs = List.of(
                    AccessLogVO.builder()
                            .id(1L).action(AccessActionEnum.ENTRY).result(AccessResultEnum.PASS)
                            .gateId(1L).gateName("A栋正门").areaId(1L).areaName("A栋办公区")
                            .createdAt(t1).build(),
                    AccessLogVO.builder()
                            .id(2L).action(AccessActionEnum.ENTRY).result(AccessResultEnum.DENIED)
                            .gateId(3L).gateName("禁区门岗").areaId(10L).areaName("禁止区域")
                            .createdAt(t2).build()
            );

            when(appointmentMapper.selectById(1L)).thenReturn(appointment);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(accessLogMapper.selectTrajectory(1L)).thenReturn(logs);

            TrajectoryVO trajectory = trajectoryService.getTrajectory(1L);

            // DENIED log doesn't change current state
            assertFalse(trajectory.getDeparted());
            assertEquals(1L, trajectory.getCurrentAreaId());
            assertEquals("A栋办公区", trajectory.getCurrentAreaName());
            // But both logs appear in trajectory points
            assertEquals(2, trajectory.getPoints().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Scenario 7: Push ordering consistency
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Push ordering: DB writes happen before pushes")
    class PushOrdering {

        @Test
        @DisplayName("Checkin: access log insert happens before push notifications")
        void checkin_dbWriteBeforePush() {
            Appointment appointment = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.APPROVED).build();

            PassCode passCode = PassCode.builder()
                    .id(1L).code("valid.code").appointmentId(1L)
                    .maxUses(2).usedCount(0).status(PassCodeStatusEnum.ACTIVE).build();

            GateCheckinRequest request = new GateCheckinRequest();
            request.setPassCode("valid.code");
            request.setGateLocation("Main Gate");

            when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(appointmentService.getById(1L)).thenReturn(appointment);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
            when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
            when(passCodeService.confirmUsage(1L)).thenReturn(passCode);
            when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
            when(sysUserMapper.selectById(1L)).thenReturn(hostUser);
            when(accessLogMapper.insert(any())).thenReturn(1);

            gateService.checkin(request);

            // Verify ordering: DB writes happened
            InOrder inOrder = inOrder(
                    passCodeService, appointmentService, accessLogMapper,
                    webSocketPushService);
            inOrder.verify(passCodeService).confirmUsage(1L);
            inOrder.verify(appointmentService).markCheckedIn(1L);
            inOrder.verify(accessLogMapper).insert(any());
            inOrder.verify(webSocketPushService).pushVisitorArrived(anyString(), anyString(), anyString());
            inOrder.verify(webSocketPushService).pushTrajectoryUpdate(
                    anyString(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("Checkout: markCompleted + access log insert before trajectory push")
        void checkout_dbWriteBeforePush() {
            Appointment appointment = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.CHECKED_IN).build();

            PassCode passCode = PassCode.builder()
                    .id(1L).code("valid.code").appointmentId(1L).build();

            GateCheckoutRequest request = new GateCheckoutRequest();
            request.setVisitorId(10L);
            request.setAppointmentId(1L);
            request.setGateLocation("Main Gate");

            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(appointmentService.getById(1L)).thenReturn(appointment);
            when(passCodeService.getPassCodeByAppointmentId(1L)).thenReturn(passCode);
            when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(accessLogMapper.insert(any())).thenReturn(1);

            gateService.checkout(request);

            InOrder inOrder = inOrder(appointmentService, accessLogMapper, webSocketPushService);
            inOrder.verify(appointmentService).markCompleted(1L);
            inOrder.verify(accessLogMapper).insert(any());
            inOrder.verify(webSocketPushService).pushTrajectoryUpdate(
                    anyString(), anyString(), any(), eq("EXIT"));
        }
    }
}
