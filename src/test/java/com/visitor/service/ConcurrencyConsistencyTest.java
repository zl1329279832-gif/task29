package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AccessLogMapper;
import com.visitor.mapper.AnomalyRecordMapper;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.ImportBatchMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.model.dto.GateCheckinRequest;
import com.visitor.model.dto.GateCheckoutRequest;
import com.visitor.model.dto.MeetingVisitorImportItem;
import com.visitor.model.dto.MeetingVisitorImportRequest;
import com.visitor.model.entity.*;
import com.visitor.model.enums.*;
import com.visitor.util.RedisLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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
 * Concurrency and consistency tests covering:
 * - Multi-gate concurrent scan: duplicate entry detection with anomaly record
 * - Checkout with distributed lock: concurrent checkout prevention
 * - Batch import blacklist assertion: proper BizException handling
 * - Area unauthorized: DENIED access log created alongside anomaly record
 * - Companion exceeded: DENIED access log created alongside anomaly record
 */
@ExtendWith(MockitoExtension.class)
class ConcurrencyConsistencyTest {

    // ═══════════════════════════════════════════════════════════════════
    // Multi-gate concurrent scan tests
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multi-gate concurrent scan — duplicate entry")
    class MultiGateDuplicateEntry {

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

        private Visitor testVisitor;
        private SysUser securityUser;

        @BeforeEach
        void setUp() {
            testVisitor = Visitor.builder()
                    .id(10L).name("Li Si").phone("13800138000").build();
            securityUser = SysUser.builder()
                    .id(2L).username("security1").role(RoleEnum.SECURITY).status(1).build();

            var auth = new UsernamePasswordAuthenticationToken(
                    "security1", null,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_SECURITY")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        @Test
        @DisplayName("Same pass code, already CHECKED_IN → idempotent return")
        void samePassCode_AlreadyCheckedIn_IdempotentReturn() {
            GateCheckinRequest request = new GateCheckinRequest();
            request.setPassCode("valid.code");
            request.setGateLocation("Main Gate");

            PassCode passCode = PassCode.builder()
                    .id(1L).code("valid.code").appointmentId(1L).build();

            Appointment checkedInAppt = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.CHECKED_IN).build();

            AccessLog existingLog = AccessLog.builder()
                    .id(100L).passCodeId(1L).appointmentId(1L)
                    .action(AccessActionEnum.ENTRY).result(AccessResultEnum.PASS)
                    .gateLocation("Main Gate").build();

            when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(appointmentService.getById(1L)).thenReturn(checkedInAppt);
            when(accessLogMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingLog);

            AccessLog result = gateService.checkin(request);

            // Returns existing log (idempotent)
            assertEquals(100L, result.getId());
            // Pass code NOT consumed again
            verify(passCodeService, never()).confirmUsage(anyLong());
            verify(accessLogMapper, never()).insert(any());
            verify(anomalyRecordMapper, never()).insert(any());
        }

        @Test
        @DisplayName("Different pass code at second gate → DUPLICATE_ENTRY anomaly + DENIED log")
        void differentPassCode_SecondGate_DuplicateEntryAnomaly() {
            GateCheckinRequest request = new GateCheckinRequest();
            request.setPassCode("second.code");
            request.setGateLocation("Side Gate");
            request.setGateId(2L);

            // Second pass code with different ID
            PassCode passCode2 = PassCode.builder()
                    .id(2L).code("second.code").appointmentId(1L).build();

            Appointment checkedInAppt = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.CHECKED_IN).build();

            // Existing log was from first pass code (id=1)
            AccessLog existingLog = AccessLog.builder()
                    .id(100L).passCodeId(1L).appointmentId(1L)
                    .action(AccessActionEnum.ENTRY).result(AccessResultEnum.PASS)
                    .gateLocation("Main Gate").build();

            Gate sideGate = Gate.builder()
                    .id(2L).name("Side Gate").areaId(3L).locationDesc("侧门")
                    .gateType(GateTypeEnum.NORMAL).status(1).build();

            when(passCodeService.verifyForScan("second.code")).thenReturn(passCode2);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(appointmentService.getById(1L)).thenReturn(checkedInAppt);
            when(accessLogMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingLog);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(areaService.getGateAndValidate(2L)).thenReturn(sideGate);
            when(accessLogMapper.insert(any())).thenReturn(1);
            when(anomalyRecordMapper.insert(any())).thenReturn(1);

            BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
            assertEquals(ErrorCode.PASS_CODE_DUPLICATE_SCAN, ex.getErrorCode());

            // CRITICAL: anomaly record with DUPLICATE_ENTRY type must be created
            verify(anomalyRecordMapper).insert(argThat(record ->
                    record.getAnomalyType() == AnomalyTypeEnum.DUPLICATE_ENTRY
                            && record.getVisitorId().equals(10L)
                            && record.getAppointmentId().equals(1L)
                            && record.getGateId() != null));

            // CRITICAL: DENIED access log must be created
            verify(accessLogMapper).insert(argThat(log ->
                    log.getResult() == AccessResultEnum.DENIED
                            && log.getAction() == AccessActionEnum.ENTRY
                            && log.getPassCodeId().equals(2L)
                            && log.getDenyReason().contains("DUPLICATE_ENTRY")));

            // Pass code should NOT have been consumed
            verify(passCodeService, never()).confirmUsage(anyLong());
        }

        @Test
        @DisplayName("No existing entry log, already CHECKED_IN → DUPLICATE_ENTRY anomaly")
        void noExistingLog_AlreadyCheckedIn_DuplicateEntryAnomaly() {
            GateCheckinRequest request = new GateCheckinRequest();
            request.setPassCode("another.code");
            request.setGateLocation("Gate X");

            PassCode passCode = PassCode.builder()
                    .id(5L).code("another.code").appointmentId(1L).build();

            Appointment checkedInAppt = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.CHECKED_IN).build();

            when(passCodeService.verifyForScan("another.code")).thenReturn(passCode);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(appointmentService.getById(1L)).thenReturn(checkedInAppt);
            when(accessLogMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(accessLogMapper.insert(any())).thenReturn(1);
            when(anomalyRecordMapper.insert(any())).thenReturn(1);

            BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
            assertEquals(ErrorCode.PASS_CODE_DUPLICATE_SCAN, ex.getErrorCode());

            verify(anomalyRecordMapper).insert(argThat(record ->
                    record.getAnomalyType() == AnomalyTypeEnum.DUPLICATE_ENTRY));
            verify(accessLogMapper).insert(argThat(log ->
                    log.getResult() == AccessResultEnum.DENIED));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Checkout with distributed lock
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Checkout distributed lock")
    class CheckoutDistributedLock {

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
        private Visitor testVisitor;

        @BeforeEach
        void setUp() {
            securityUser = SysUser.builder()
                    .id(2L).username("security1").role(RoleEnum.SECURITY).status(1).build();
            testVisitor = Visitor.builder()
                    .id(10L).name("Li Si").phone("13800138000").build();

            var auth = new UsernamePasswordAuthenticationToken(
                    "security1", null,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_SECURITY")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        @Test
        @DisplayName("Checkout fails when lock is held by another gate")
        void checkout_LockHeld_Fails() {
            Appointment checkedInAppt = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.CHECKED_IN).build();

            GateCheckoutRequest request = new GateCheckoutRequest();
            request.setVisitorId(10L);
            request.setAppointmentId(1L);
            request.setGateLocation("Gate A");

            when(appointmentService.getById(1L)).thenReturn(checkedInAppt);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(null);

            BizException ex = assertThrows(BizException.class, () -> gateService.checkout(request));
            assertEquals(ErrorCode.PASS_CODE_DUPLICATE_SCAN, ex.getErrorCode());

            // Should NOT have marked as completed
            verify(appointmentService, never()).markCompleted(anyLong());
            verify(accessLogMapper, never()).insert(any());
        }

        @Test
        @DisplayName("Checkout succeeds and releases lock")
        void checkout_Success_LockReleased() {
            Appointment checkedInAppt = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.CHECKED_IN).build();

            GateCheckoutRequest request = new GateCheckoutRequest();
            request.setVisitorId(10L);
            request.setAppointmentId(1L);
            request.setGateLocation("Gate A");

            when(appointmentService.getById(1L)).thenReturn(checkedInAppt);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(sysUserMapper.findByUsername("security1")).thenReturn(securityUser);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(accessLogMapper.insert(any())).thenReturn(1);

            AccessLog result = gateService.checkout(request);

            assertNotNull(result);
            assertEquals(AccessActionEnum.EXIT, result.getAction());
            verify(appointmentService).markCompleted(1L);
            // Lock MUST be released
            verify(redisLock).unlock(anyString(), eq("lock-value"));
        }

        @Test
        @DisplayName("Checkout lock released even on exception")
        void checkout_Exception_LockReleased() {
            Appointment checkedInAppt = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.CHECKED_IN).build();

            GateCheckoutRequest request = new GateCheckoutRequest();
            request.setVisitorId(10L);
            request.setAppointmentId(1L);

            when(appointmentService.getById(1L)).thenReturn(checkedInAppt);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            doThrow(new RuntimeException("DB error"))
                    .when(appointmentService).markCompleted(anyLong());

            assertThrows(RuntimeException.class, () -> gateService.checkout(request));

            // Lock MUST be released even on exception
            verify(redisLock).unlock(anyString(), eq("lock-value"));
        }

        @Test
        @DisplayName("Checkout re-reads appointment under lock — detects concurrent completion")
        void checkout_ReReadUnderLock_DetectsConcurrentCompletion() {
            Appointment checkedInAppt = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.CHECKED_IN).build();

            Appointment completedAppt = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.COMPLETED).build();

            GateCheckoutRequest request = new GateCheckoutRequest();
            request.setVisitorId(10L);
            request.setAppointmentId(1L);

            AccessLog existingExitLog = AccessLog.builder()
                    .id(200L).appointmentId(1L).action(AccessActionEnum.EXIT)
                    .result(AccessResultEnum.PASS).build();

            // First read: CHECKED_IN, second read (under lock): COMPLETED
            when(appointmentService.getById(1L))
                    .thenReturn(checkedInAppt)
                    .thenReturn(completedAppt);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(accessLogMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingExitLog);

            AccessLog result = gateService.checkout(request);

            // Returns existing exit log (idempotent under lock)
            assertEquals(200L, result.getId());
            verify(appointmentService, never()).markCompleted(anyLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Area unauthorized — DENIED access log
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Area unauthorized creates DENIED access log")
    class AreaUnauthorizedDeniedLog {

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

        private Visitor testVisitor;

        @BeforeEach
        void setUp() {
            testVisitor = Visitor.builder()
                    .id(10L).name("Li Si").phone("13800138000").build();

            var auth = new UsernamePasswordAuthenticationToken(
                    "security1", null,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_SECURITY")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        @Test
        @DisplayName("Area unauthorized creates BOTH anomaly record AND DENIED access log")
        void areaUnauthorized_CreatesBothRecords() {
            Appointment approvedAppt = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.APPROVED).build();

            PassCode passCode = PassCode.builder()
                    .id(1L).code("valid.code").appointmentId(1L)
                    .maxUses(2).usedCount(0).status(PassCodeStatusEnum.ACTIVE).build();

            Gate gate = Gate.builder()
                    .id(1L).name("B栋正门").areaId(2L).locationDesc("B栋")
                    .gateType(GateTypeEnum.NORMAL).status(1).build();

            GateCheckinRequest request = new GateCheckinRequest();
            request.setPassCode("valid.code");
            request.setGateId(1L);
            request.setGateLocation("B栋正门");

            when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(appointmentService.getById(1L)).thenReturn(approvedAppt);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
            when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
            when(areaService.getGateAndValidate(1L)).thenReturn(gate);
            when(areaService.isGateEntryAllowed(gate)).thenReturn(true);
            doThrow(new BizException(ErrorCode.UNAUTHORIZED_AREA_ACCESS))
                    .when(areaAuthorizationService).validateGateAccess(eq(1L), eq(1L), any());
            when(anomalyRecordMapper.insert(any())).thenReturn(1);
            when(accessLogMapper.insert(any())).thenReturn(1);

            BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
            assertEquals(ErrorCode.UNAUTHORIZED_AREA_ACCESS, ex.getErrorCode());

            // BOTH anomaly record AND denied access log must be created
            verify(anomalyRecordMapper).insert(argThat(record ->
                    record.getAnomalyType() == AnomalyTypeEnum.UNAUTHORIZED_AREA
                            && record.getGateId().equals(1L)
                            && record.getAreaId().equals(2L)));

            verify(accessLogMapper).insert(argThat(log ->
                    log.getResult() == AccessResultEnum.DENIED
                            && log.getAction() == AccessActionEnum.ENTRY
                            && log.getPassCodeId().equals(1L)
                            && log.getDenyReason().contains("UNAUTHORIZED_AREA")
                            && log.getGateId().equals(1L)
                            && log.getAreaId().equals(2L)));

            // Pass code NOT consumed
            verify(passCodeService, never()).confirmUsage(anyLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Companion exceeded — DENIED access log
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Companion exceeded creates DENIED access log")
    class CompanionExceededDeniedLog {

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

        private Visitor testVisitor;

        @BeforeEach
        void setUp() {
            testVisitor = Visitor.builder()
                    .id(10L).name("Li Si").phone("13800138000").build();

            var auth = new UsernamePasswordAuthenticationToken(
                    "security1", null,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_SECURITY")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        @Test
        @DisplayName("Companion exceeded creates BOTH anomaly record AND DENIED access log")
        void companionExceeded_CreatesBothRecords() {
            Appointment approvedAppt = Appointment.builder()
                    .id(1L).appointNo("APT001").visitorId(10L).hostId(1L)
                    .status(AppointmentStatusEnum.APPROVED).maxCompanions(2).build();

            PassCode passCode = PassCode.builder()
                    .id(1L).code("valid.code").appointmentId(1L)
                    .maxUses(4).usedCount(0).status(PassCodeStatusEnum.ACTIVE).build();

            Gate gate = Gate.builder()
                    .id(1L).name("A栋正门").areaId(1L).locationDesc("A栋")
                    .gateType(GateTypeEnum.NORMAL).status(1).build();

            GateCheckinRequest request = new GateCheckinRequest();
            request.setPassCode("valid.code");
            request.setGateId(1L);
            request.setGateLocation("A栋正门");
            request.setCompanionCount(5);

            when(passCodeService.verifyForScan("valid.code")).thenReturn(passCode);
            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(appointmentService.getById(1L)).thenReturn(approvedAppt);
            when(visitorService.getById(10L)).thenReturn(testVisitor);
            when(appointmentService.hasUndepartedAppointment(10L, 1L)).thenReturn(false);
            when(blacklistService.check(anyString(), any(), anyString())).thenReturn(null);
            when(areaService.getGateAndValidate(1L)).thenReturn(gate);
            when(areaService.isGateEntryAllowed(gate)).thenReturn(true);
            doNothing().when(areaAuthorizationService).validateGateAccess(eq(1L), eq(1L), any());
            when(anomalyRecordMapper.insert(any())).thenReturn(1);
            when(accessLogMapper.insert(any())).thenReturn(1);

            BizException ex = assertThrows(BizException.class, () -> gateService.checkin(request));
            assertEquals(ErrorCode.COMPANION_LIMIT_EXCEEDED, ex.getErrorCode());

            // BOTH anomaly record AND denied access log must be created
            verify(anomalyRecordMapper).insert(argThat(record ->
                    record.getAnomalyType() == AnomalyTypeEnum.COMPANION_ANOMALY
                            && record.getDescription().contains("5")
                            && record.getDescription().contains("2")));

            verify(accessLogMapper).insert(argThat(log ->
                    log.getResult() == AccessResultEnum.DENIED
                            && log.getAction() == AccessActionEnum.ENTRY
                            && log.getPassCodeId().equals(1L)
                            && log.getDenyReason().contains("COMPANION_LIMIT_EXCEEDED")));

            // Pass code NOT consumed
            verify(passCodeService, never()).confirmUsage(anyLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Batch import blacklist assertion
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Batch import blacklist assertion")
    class BatchImportBlacklistAssertion {

        @InjectMocks
        private ImportService importService;

        @Mock private ImportBatchMapper importBatchMapper;
        @Mock private SysUserMapper sysUserMapper;
        @Mock private VisitorService visitorService;
        @Mock private AppointmentService appointmentService;
        @Mock private BlacklistService blacklistService;
        @Mock private AppointmentMapper appointmentMapper;
        @Mock private AreaAuthorizationService areaAuthorizationService;
        @Mock private RedisLock redisLock;
        @Spy private ObjectMapper objectMapper = new ObjectMapper();

        private SysUser operator;

        @BeforeEach
        void setUp() {
            operator = SysUser.builder()
                    .id(1L).username("employee1").realName("Zhang San")
                    .role(RoleEnum.EMPLOYEE).status(1).build();

            var auth = new UsernamePasswordAuthenticationToken(
                    "employee1", null,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        @Test
        @DisplayName("Blacklisted visitor in batch import recorded as failure with proper reason")
        void blacklistedVisitor_RecordedAsFailure() {
            MeetingVisitorImportRequest request = new MeetingVisitorImportRequest();
            request.setHostId(1L);

            MeetingVisitorImportItem item = new MeetingVisitorImportItem();
            item.setName("Blacklisted Person");
            item.setPhone("13800000001");
            item.setIdCard("310101199001011234");
            item.setExpectedArrive(LocalDateTime.now().plusDays(1));
            request.setVisitors(List.of(item));

            Visitor visitor = Visitor.builder()
                    .id(10L).name("Blacklisted Person")
                    .idCard("310101199001011234").phone("13800000001").build();

            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(sysUserMapper.findByUsername("employee1")).thenReturn(operator);
            when(visitorService.registerOrFind(any())).thenReturn(visitor);
            // assertNotBlacklisted throws BizException
            doThrow(new BizException(ErrorCode.BLACKLIST_HIT, "Security threat"))
                    .when(blacklistService).assertNotBlacklisted(
                            "Blacklisted Person", "310101199001011234", "13800000001");
            when(importBatchMapper.insert(any())).thenReturn(1);

            ImportBatch result = importService.importMeetingVisitors(request);

            assertEquals(1, result.getTotalCount());
            assertEquals(0, result.getSuccessCount());
            assertEquals(1, result.getFailCount());
            assertEquals(ImportStatusEnum.FAILED, result.getStatus());
            assertTrue(result.getFailDetail().contains("Security threat"));

            // Appointment should NOT have been created
            verify(appointmentMapper, never()).insert(any());
        }

        @Test
        @DisplayName("Duplicate appointment in batch import recorded as failure")
        void duplicateAppointment_RecordedAsFailure() {
            MeetingVisitorImportRequest request = new MeetingVisitorImportRequest();
            request.setHostId(1L);

            MeetingVisitorImportItem item = new MeetingVisitorImportItem();
            item.setName("Repeat Visitor");
            item.setPhone("13800000002");
            item.setExpectedArrive(LocalDateTime.now().plusDays(1));
            item.setExpectedLeave(LocalDateTime.now().plusDays(1).plusHours(2));
            request.setVisitors(List.of(item));

            Visitor visitor = Visitor.builder()
                    .id(20L).name("Repeat Visitor").phone("13800000002").build();

            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(sysUserMapper.findByUsername("employee1")).thenReturn(operator);
            when(visitorService.registerOrFind(any())).thenReturn(visitor);
            doNothing().when(blacklistService).assertNotBlacklisted(anyString(), any(), anyString());
            when(appointmentMapper.countDuplicate(anyLong(), anyLong(), any(), any(), any()))
                    .thenReturn(1);
            when(importBatchMapper.insert(any())).thenReturn(1);

            ImportBatch result = importService.importMeetingVisitors(request);

            assertEquals(1, result.getFailCount());
            assertTrue(result.getFailDetail().contains("Duplicate appointment"));
            verify(appointmentMapper, never()).insert(any());
        }

        @Test
        @DisplayName("Batch import with mixed results: blacklist + duplicate + success")
        void mixedResults_BlacklistDuplicateSuccess() {
            MeetingVisitorImportRequest request = new MeetingVisitorImportRequest();
            request.setHostId(1L);

            MeetingVisitorImportItem goodItem = new MeetingVisitorImportItem();
            goodItem.setName("Good Visitor");
            goodItem.setPhone("13800000001");
            goodItem.setExpectedArrive(LocalDateTime.now().plusDays(1));
            goodItem.setExpectedLeave(LocalDateTime.now().plusDays(1).plusHours(2));

            MeetingVisitorImportItem blacklistedItem = new MeetingVisitorImportItem();
            blacklistedItem.setName("Bad Visitor");
            blacklistedItem.setPhone("13800000002");
            blacklistedItem.setIdCard("310101199002022345");
            blacklistedItem.setExpectedArrive(LocalDateTime.now().plusDays(1));

            request.setVisitors(List.of(goodItem, blacklistedItem));

            Visitor goodVisitor = Visitor.builder().id(10L).name("Good Visitor").phone("13800000001").build();
            Visitor badVisitor = Visitor.builder().id(20L).name("Bad Visitor").phone("13800000002")
                    .idCard("310101199002022345").build();

            when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
            when(sysUserMapper.findByUsername("employee1")).thenReturn(operator);
            when(sysUserMapper.selectById(1L)).thenReturn(operator);
            when(visitorService.registerOrFind(any()))
                    .thenReturn(goodVisitor)
                    .thenReturn(badVisitor);
            // Good visitor passes blacklist, bad visitor fails
            doNothing().when(blacklistService).assertNotBlacklisted(
                    eq("Good Visitor"), any(), eq("13800000001"));
            doThrow(new BizException(ErrorCode.BLACKLIST_HIT, "Known threat"))
                    .when(blacklistService).assertNotBlacklisted(
                            eq("Bad Visitor"), eq("310101199002022345"), eq("13800000002"));
            when(appointmentMapper.countDuplicate(anyLong(), anyLong(), any(), any(), any()))
                    .thenReturn(0);
            when(appointmentMapper.insert(any())).thenReturn(1);
            when(importBatchMapper.insert(any())).thenReturn(1);

            ImportBatch result = importService.importMeetingVisitors(request);

            assertEquals(2, result.getTotalCount());
            assertEquals(1, result.getSuccessCount());
            assertEquals(1, result.getFailCount());
            assertEquals(ImportStatusEnum.PARTIAL_FAIL, result.getStatus());
            assertTrue(result.getFailDetail().contains("Known threat"));
        }
    }
}
