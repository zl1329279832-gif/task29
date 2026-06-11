package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AccessLogMapper;
import com.visitor.mapper.AnomalyRecordMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.model.dto.AnomalyReleaseRequest;
import com.visitor.model.dto.GateCheckinRequest;
import com.visitor.model.dto.GateCheckoutRequest;
import com.visitor.model.entity.*;
import com.visitor.model.enums.*;
import com.visitor.model.vo.AccessLogVO;
import com.visitor.util.RedisLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GateService {

    private final AccessLogMapper accessLogMapper;
    private final AnomalyRecordMapper anomalyRecordMapper;
    private final SysUserMapper sysUserMapper;
    private final PassCodeService passCodeService;
    private final AppointmentService appointmentService;
    private final VisitorService visitorService;
    private final BlacklistService blacklistService;
    private final AreaService areaService;
    private final AreaAuthorizationService areaAuthorizationService;
    private final WebSocketPushService webSocketPushService;
    private final RedisLock redisLock;

    private static final String GATE_CHECKIN_LOCK_PREFIX = "gate:checkin:";
    private static final String GATE_CHECKOUT_LOCK_PREFIX = "gate:checkout:";

    /**
     * Visitor check-in by scanning QR pass code.
     *
     * Flow (all under distributed lock on appointmentId):
     *   1. Phase-1 pass code verify  (read-only, scan lock)
     *   2. Appointment state check    (must be APPROVED)
     *   3. Undeparted-record check    (visitor must not have an open CHECKED_IN)
     *   4. Blacklist re-check         (before consuming the pass code)
     *   5. Phase-2 pass code confirm  (commit usage count)
     *   6. Mark CHECKED_IN + access log + WebSocket push
     */
    @Transactional
    public AccessLog checkin(GateCheckinRequest request) {
        // ── Phase 1: Read-only pass code validation ─────────────────────
        PassCode passCode = passCodeService.verifyForScan(request.getPassCode());

        // ── Acquire appointment-level lock ──────────────────────────────
        Long appointmentId = passCode.getAppointmentId();
        String lockKey = GATE_CHECKIN_LOCK_PREFIX + appointmentId;
        String lockValue = redisLock.tryLock(lockKey, Duration.ofSeconds(30));
        if (lockValue == null) {
            throw new BizException(ErrorCode.PASS_CODE_DUPLICATE_SCAN,
                    "该预约正在处理入场，请勿重复扫码");
        }

        try {
            // ── 2. Appointment state validation ─────────────────────────
            Appointment appointment = appointmentService.getById(appointmentId);
            if (appointment == null) {
                throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
            }

            // Idempotent: if already CHECKED_IN, return existing entry log
            if (appointment.getStatus() == AppointmentStatusEnum.CHECKED_IN) {
                AccessLog existingLog = findLatestEntryLog(appointmentId);
                if (existingLog != null) {
                    log.info("Idempotent checkin: appointment {} already CHECKED_IN, returning existing log",
                            appointmentId);
                    return existingLog;
                }
                // Edge case: CHECKED_IN but no entry log (data inconsistency) → fall through
            } else if (appointment.getStatus() != AppointmentStatusEnum.APPROVED) {
                throw new BizException(ErrorCode.APPOINTMENT_STATUS_INVALID,
                        "appointment not in APPROVED state, current: " + appointment.getStatus());
            }

            // ── 3. Undeparted-record check ──────────────────────────────
            // Block re-entry if the visitor has another CHECKED_IN appointment (hasn't left yet)
            Visitor visitor = visitorService.getById(appointment.getVisitorId());
            if (hasUndepartedRecord(visitor.getId(), appointmentId)) {
                throw new BizException(ErrorCode.NO_REENTRY_WITHOUT_EXIT,
                        "访客 " + visitor.getName() + " 尚有未离场记录，请先完成离场");
            }

            // ── 4. Blacklist re-check at gate (BEFORE consuming pass code) ──
            Blacklist bl = blacklistService.check(visitor.getName(), visitor.getIdCard(), visitor.getPhone());
            if (bl != null) {
                handleBlacklistDenial(passCode, visitor, appointment, bl, request.getGateLocation(),
                        request.getGateId());
                throw new BizException(ErrorCode.BLACKLIST_HIT, bl.getReason());
            }

            // ── 5a. Gate validation + area authorization check ──────────────
            Gate gate = null;
            Long resolvedGateId = null;
            Long resolvedAreaId = null;
            if (request.getGateId() != null) {
                gate = areaService.getGateAndValidate(request.getGateId());
                if (!areaService.isGateEntryAllowed(gate)) {
                    throw new BizException(ErrorCode.GATE_AREA_MISMATCH,
                            "该门岗仅允许出场，不允许入场");
                }
                try {
                    areaAuthorizationService.validateGateAccess(
                            appointmentId, gate.getId(), LocalDateTime.now());
                } catch (BizException e) {
                    // Unauthorized area access — create anomaly, DENIED access log, and push alert
                    createAnomalyWithGate(visitor.getId(), appointmentId,
                            AnomalyTypeEnum.UNAUTHORIZED_AREA,
                            "访客 " + visitor.getName() + " 尝试进入未授权区域: " + gate.getName(),
                            null, gate.getId(), gate.getAreaId());

                    // Write DENIED access log for area violation (trajectory consistency)
                    AccessLog deniedLog = AccessLog.builder()
                            .passCodeId(passCode.getId())
                            .visitorId(visitor.getId())
                            .appointmentId(appointmentId)
                            .action(AccessActionEnum.ENTRY)
                            .gateLocation(request.getGateLocation())
                            .result(AccessResultEnum.DENIED)
                            .denyReason("UNAUTHORIZED_AREA: " + gate.getName())
                            .gateId(gate.getId())
                            .areaId(gate.getAreaId())
                            .build();
                    accessLogMapper.insert(deniedLog);

                    webSocketPushService.pushAreaViolationAlert(
                            visitor.getName(), gate.getLocationDesc(), gate.getName(),
                            "未授权进入区域");
                    throw e;
                }
                resolvedGateId = gate.getId();
                resolvedAreaId = gate.getAreaId();
            }

            // ── 5b. Companion count check ──────────────────────────────
            if (request.getCompanionCount() != null && appointment.getMaxCompanions() != null) {
                if (request.getCompanionCount() > appointment.getMaxCompanions()) {
                    createAnomalyWithGate(visitor.getId(), appointmentId,
                            AnomalyTypeEnum.COMPANION_ANOMALY,
                            "随行人数超限: 实际 " + request.getCompanionCount()
                                    + " 人, 限制 " + appointment.getMaxCompanions() + " 人",
                            null, resolvedGateId, resolvedAreaId);

                    // Write DENIED access log for companion anomaly (trajectory consistency)
                    AccessLog deniedLog = AccessLog.builder()
                            .passCodeId(passCode.getId())
                            .visitorId(visitor.getId())
                            .appointmentId(appointmentId)
                            .action(AccessActionEnum.ENTRY)
                            .gateLocation(request.getGateLocation())
                            .result(AccessResultEnum.DENIED)
                            .denyReason("COMPANION_EXCEEDED: actual=" + request.getCompanionCount()
                                    + " max=" + appointment.getMaxCompanions())
                            .gateId(resolvedGateId)
                            .areaId(resolvedAreaId)
                            .build();
                    accessLogMapper.insert(deniedLog);

                    webSocketPushService.pushCompanionAnomalyAlert(
                            visitor.getName(), request.getCompanionCount(),
                            appointment.getMaxCompanions(),
                            gate != null ? gate.getName() : request.getGateLocation());
                    throw new BizException(ErrorCode.COMPANION_LIMIT_EXCEEDED,
                            "随行人数 " + request.getCompanionCount()
                                    + " 超过限制 " + appointment.getMaxCompanions());
                }
            }

            // ── 5. Phase 2: Commit pass code usage ──────────────────────
            passCodeService.confirmUsage(passCode.getId());

            // ── 6. Mark checked in, create log, push notification ───────
            appointmentService.markCheckedIn(appointmentId);
            visitorService.incrementVisitCount(visitor.getId());

            String username = SecurityContextHolder.getContext().getAuthentication().getName();
            SysUser operator = sysUserMapper.findByUsername(username);

            AccessLog accessLog = AccessLog.builder()
                    .passCodeId(passCode.getId())
                    .visitorId(visitor.getId())
                    .appointmentId(appointmentId)
                    .action(AccessActionEnum.ENTRY)
                    .gateLocation(request.getGateLocation())
                    .result(AccessResultEnum.PASS)
                    .operatorId(operator != null ? operator.getId() : null)
                    .gateId(resolvedGateId)
                    .areaId(resolvedAreaId)
                    .build();
            accessLogMapper.insert(accessLog);

            // Push arrival notification to host
            SysUser host = sysUserMapper.selectById(appointment.getHostId());
            if (host != null) {
                webSocketPushService.pushVisitorArrived(
                        host.getId().toString(), visitor.getName(), appointment.getAppointNo());
            }

            // Push trajectory update to security
            webSocketPushService.pushTrajectoryUpdate(
                    visitor.getName(),
                    gate != null ? gate.getName() : request.getGateLocation(),
                    gate != null ? gate.getLocationDesc() : null,
                    "ENTRY");

            log.info("Visitor {} checked in for appointment {} at gate {}",
                    visitor.getName(), appointment.getAppointNo(),
                    gate != null ? gate.getName() : request.getGateLocation());
            return accessLog;
        } finally {
            redisLock.unlock(lockKey, lockValue);
        }
    }

    /**
     * Visitor check-out (departure).
     * Idempotent: if already COMPLETED, returns existing exit log.
     * Uses distributed lock to prevent concurrent duplicate exits.
     */
    @Transactional
    public AccessLog checkout(GateCheckoutRequest request) {
        Long appointmentId = request.getAppointmentId();

        // ── Acquire appointment-level lock for checkout ────────────────
        String lockKey = GATE_CHECKOUT_LOCK_PREFIX + appointmentId;
        String lockValue = redisLock.tryLock(lockKey, Duration.ofSeconds(30));
        if (lockValue == null) {
            throw new BizException(ErrorCode.PASS_CODE_DUPLICATE_SCAN,
                    "该预约正在处理离场，请勿重复操作");
        }

        try {
            Appointment appointment = appointmentService.getById(appointmentId);
            if (appointment == null) {
                throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
            }

            // Idempotent: already completed
            if (appointment.getStatus() == AppointmentStatusEnum.COMPLETED) {
                AccessLog existingLog = findLatestExitLog(appointmentId);
                if (existingLog != null) {
                    log.info("Idempotent checkout: appointment {} already COMPLETED, returning existing log",
                            appointmentId);
                    return existingLog;
                }
                throw new BizException(ErrorCode.ALREADY_DEPARTED);
            }

            if (appointment.getStatus() != AppointmentStatusEnum.CHECKED_IN) {
                throw new BizException(ErrorCode.NOT_CHECKED_IN);
            }

            // Gate validation for exit
            Gate gate = null;
            Long resolvedGateId = null;
            Long resolvedAreaId = null;
            if (request.getGateId() != null) {
                gate = areaService.getGateAndValidate(request.getGateId());
                if (!areaService.isGateExitAllowed(gate)) {
                    throw new BizException(ErrorCode.GATE_AREA_MISMATCH,
                            "该门岗仅允许入场，不允许出场");
                }
                resolvedGateId = gate.getId();
                resolvedAreaId = gate.getAreaId();
            }

            // Resolve passCodeId for trajectory link consistency
            PassCode passCode = passCodeService.getPassCodeByAppointmentId(appointmentId);

            // Mark completed
            appointmentService.markCompleted(appointmentId);

            // Create access log with passCodeId for trajectory consistency
            String username = SecurityContextHolder.getContext().getAuthentication().getName();
            SysUser operator = sysUserMapper.findByUsername(username);

            AccessLog accessLog = AccessLog.builder()
                    .passCodeId(passCode != null ? passCode.getId() : null)
                    .visitorId(request.getVisitorId())
                    .appointmentId(appointmentId)
                    .action(AccessActionEnum.EXIT)
                    .gateLocation(request.getGateLocation())
                    .result(AccessResultEnum.PASS)
                    .operatorId(operator != null ? operator.getId() : null)
                    .gateId(resolvedGateId)
                    .areaId(resolvedAreaId)
                    .build();
            accessLogMapper.insert(accessLog);

            // Push trajectory update AFTER DB write
            Visitor visitor = visitorService.getById(request.getVisitorId());
            webSocketPushService.pushTrajectoryUpdate(
                    visitor.getName(),
                    gate != null ? gate.getName() : request.getGateLocation(),
                    gate != null ? gate.getLocationDesc() : null,
                    "EXIT");

            log.info("Visitor {} checked out for appointment {} at gate {}",
                    request.getVisitorId(), appointment.getAppointNo(),
                    gate != null ? gate.getName() : request.getGateLocation());
            return accessLog;
        } finally {
            redisLock.unlock(lockKey, lockValue);
        }
    }

    /**
     * Security manual anomaly release.
     */
    @Transactional
    public AccessLog anomalyRelease(AnomalyReleaseRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser security = sysUserMapper.findByUsername(username);

        Long resolvedGateId = null;
        Long resolvedAreaId = null;
        if (request.getGateId() != null) {
            Gate gate = areaService.getGateAndValidate(request.getGateId());
            resolvedGateId = gate.getId();
            resolvedAreaId = gate.getAreaId();
        }

        AnomalyRecord anomaly = AnomalyRecord.builder()
                .visitorId(request.getVisitorId())
                .appointmentId(request.getAppointmentId())
                .gateId(resolvedGateId)
                .areaId(resolvedAreaId)
                .anomalyType(request.getAnomalyType())
                .description(request.getDescription())
                .securityId(security != null ? security.getId() : null)
                .status(AnomalyStatusEnum.RESOLVED)
                .handleResult("Manual release by security")
                .build();
        anomalyRecordMapper.insert(anomaly);

        AccessLog accessLog = AccessLog.builder()
                .visitorId(request.getVisitorId())
                .appointmentId(request.getAppointmentId())
                .action(AccessActionEnum.ENTRY)
                .gateLocation(request.getGateLocation())
                .result(AccessResultEnum.ANOMALY)
                .denyReason("Anomaly release: " + request.getAnomalyType())
                .operatorId(security != null ? security.getId() : null)
                .gateId(resolvedGateId)
                .areaId(resolvedAreaId)
                .build();
        accessLogMapper.insert(accessLog);

        Visitor visitor = visitorService.getById(request.getVisitorId());
        webSocketPushService.pushAnomalyAlert(
                request.getAnomalyType().name(), visitor.getName(), request.getDescription());

        log.info("Anomaly release for visitor {} by security {}", request.getVisitorId(), username);
        return accessLog;
    }

    public Page<AccessLogVO> getAccessLogs(Long visitorId, Long appointmentId, int page, int size) {
        List<AccessLogVO> records = accessLogMapper.selectAccessLogList(visitorId, appointmentId);
        Page<AccessLogVO> result = new Page<>(page, size);
        int start = (page - 1) * size;
        int end = Math.min(start + size, records.size());
        if (start < records.size()) {
            result.setRecords(records.subList(start, end));
        }
        result.setTotal(records.size());
        return result;
    }

    // ── Private helpers ─────────────────────────────────────────────────

    /**
     * Check if the visitor has any CHECKED_IN appointment OTHER than the current one.
     * If so, they haven't departed and re-entry is blocked.
     */
    private boolean hasUndepartedRecord(Long visitorId, Long currentAppointmentId) {
        return appointmentService.hasUndepartedAppointment(visitorId, currentAppointmentId);
    }

    /**
     * Handle blacklist denial: create anomaly record, denied access log, and push alert.
     */
    private void handleBlacklistDenial(PassCode passCode, Visitor visitor,
                                        Appointment appointment, Blacklist bl,
                                        String gateLocation, Long gateId) {
        Gate gate = null;
        Long resolvedGateId = gateId;
        Long resolvedAreaId = null;
        if (gateId != null) {
            gate = areaService.getGateAndValidate(gateId);
            resolvedAreaId = gate.getAreaId();
        }

        createAnomalyWithGate(visitor.getId(), appointment.getId(),
                AnomalyTypeEnum.BLACKLIST_ATTEMPT,
                "Blacklisted visitor attempted entry: " + bl.getReason(),
                null, resolvedGateId, resolvedAreaId);

        webSocketPushService.pushBlacklistAlert(visitor.getName(), bl.getReason(), gateLocation);

        AccessLog deniedLog = AccessLog.builder()
                .passCodeId(passCode.getId())
                .visitorId(visitor.getId())
                .appointmentId(appointment.getId())
                .action(AccessActionEnum.ENTRY)
                .gateLocation(gateLocation)
                .result(AccessResultEnum.DENIED)
                .denyReason("BLACKLISTED: " + bl.getReason())
                .gateId(resolvedGateId)
                .areaId(resolvedAreaId)
                .build();
        accessLogMapper.insert(deniedLog);

        log.warn("Blacklist denial: visitor={}, appointment={}, reason={}",
                visitor.getName(), appointment.getAppointNo(), bl.getReason());
    }

    private void createAnomaly(Long visitorId, Long appointmentId, AnomalyTypeEnum type,
                                String description, Long securityId) {
        createAnomalyWithGate(visitorId, appointmentId, type, description, securityId, null, null);
    }

    private void createAnomalyWithGate(Long visitorId, Long appointmentId, AnomalyTypeEnum type,
                                        String description, Long securityId,
                                        Long gateId, Long areaId) {
        AnomalyRecord record = AnomalyRecord.builder()
                .visitorId(visitorId)
                .appointmentId(appointmentId)
                .gateId(gateId)
                .areaId(areaId)
                .anomalyType(type)
                .description(description)
                .securityId(securityId)
                .status(AnomalyStatusEnum.OPEN)
                .build();
        anomalyRecordMapper.insert(record);
    }

    private AccessLog findLatestEntryLog(Long appointmentId) {
        LambdaQueryWrapper<AccessLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AccessLog::getAppointmentId, appointmentId)
                .eq(AccessLog::getAction, AccessActionEnum.ENTRY)
                .eq(AccessLog::getResult, AccessResultEnum.PASS)
                .orderByDesc(AccessLog::getCreatedAt)
                .last("LIMIT 1");
        return accessLogMapper.selectOne(wrapper);
    }

    private AccessLog findLatestExitLog(Long appointmentId) {
        LambdaQueryWrapper<AccessLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AccessLog::getAppointmentId, appointmentId)
                .eq(AccessLog::getAction, AccessActionEnum.EXIT)
                .eq(AccessLog::getResult, AccessResultEnum.PASS)
                .orderByDesc(AccessLog::getCreatedAt)
                .last("LIMIT 1");
        return accessLogMapper.selectOne(wrapper);
    }
}
