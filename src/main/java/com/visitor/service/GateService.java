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
import com.visitor.model.dto.GateScanRequest;
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
    private final WebSocketPushService webSocketPushService;
    private final RedisLock redisLock;
    private final GateManageService gateManageService;
    private final AreaService areaService;
    private final AreaAuthorizationService areaAuthorizationService;
    private final TrajectoryService trajectoryService;

    private static final String GATE_CHECKIN_LOCK_PREFIX = "gate:checkin:";

    /**
     * Visitor check-in by scanning QR pass code.
     *
     * Flow (all under distributed lock on appointmentId):
     *   1. Phase-1 pass code verify  (read-only, scan lock)
     *   2. Resolve gate entity and area (if gateId provided)
     *   3. Appointment state check    (must be APPROVED)
     *   4. Undeparted-record check    (visitor must not have an open CHECKED_IN)
     *   5. Blacklist re-check         (before consuming the pass code)
     *   6. Area authorization check   (if gate has an area)
     *   7. Phase-2 pass code confirm  (commit usage count)
     *   8. Mark CHECKED_IN + access log + WebSocket push + trajectory
     */
    @Transactional
    public AccessLog checkin(GateCheckinRequest request) {
        // ── Phase 1: Read-only pass code validation ─────────────────────
        PassCode passCode = passCodeService.verifyForScan(request.getPassCode());

        // ── Resolve gate and area ───────────────────────────────────────
        Gate gate = null;
        Area area = null;
        if (request.getGateId() != null) {
            gate = gateManageService.validateGateActive(request.getGateId());
            area = areaService.getById(gate.getAreaId());
        }

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
            Visitor visitor = visitorService.getById(appointment.getVisitorId());
            if (hasUndepartedRecord(visitor.getId(), appointmentId)) {
                throw new BizException(ErrorCode.NO_REENTRY_WITHOUT_EXIT,
                        "访客 " + visitor.getName() + " 尚有未离场记录，请先完成离场");
            }

            // ── 4. Blacklist re-check at gate (BEFORE consuming pass code) ──
            Blacklist bl = blacklistService.check(visitor.getName(), visitor.getIdCard(), visitor.getPhone());
            if (bl != null) {
                handleBlacklistDenial(passCode, visitor, appointment, bl,
                        request.getGateLocation(), gate, area);
                throw new BizException(ErrorCode.BLACKLIST_HIT, bl.getReason());
            }

            // ── 5. Area authorization check (if gate has an area) ───────
            if (area != null) {
                if (!areaAuthorizationService.checkAuthorization(appointmentId, area.getId())) {
                    handleAreaUnauthorized(passCode, visitor, appointment, gate, area);
                    throw new BizException(ErrorCode.AREA_UNAUTHORIZED,
                            "访客 " + visitor.getName() + " 未授权进入区域 " + area.getAreaName());
                }
            }

            // ── 6. Phase 2: Commit pass code usage ──────────────────────
            passCodeService.confirmUsage(passCode.getId());

            // ── 7. Mark checked in, create log, push notification ───────
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
                    .gateId(gate != null ? gate.getId() : null)
                    .areaId(area != null ? area.getId() : null)
                    .result(AccessResultEnum.PASS)
                    .operatorId(operator != null ? operator.getId() : null)
                    .build();
            accessLogMapper.insert(accessLog);

            // Record trajectory entry
            if (gate != null && area != null) {
                trajectoryService.recordEntry(visitor.getId(), appointmentId, gate.getId(), area.getId());
            }

            // Push arrival notification to host
            SysUser host = sysUserMapper.selectById(appointment.getHostId());
            if (host != null) {
                webSocketPushService.pushVisitorArrived(
                        host.getId().toString(), visitor.getName(), appointment.getAppointNo());
            }

            log.info("Visitor {} checked in for appointment {} at gate {}",
                    visitor.getName(), appointment.getAppointNo(),
                    gate != null ? gate.getGateName() : request.getGateLocation());
            return accessLog;
        } finally {
            redisLock.unlock(lockKey, lockValue);
        }
    }

    /**
     * Visitor check-out (departure).
     * Idempotent: if already COMPLETED, returns existing exit log.
     */
    @Transactional
    public AccessLog checkout(GateCheckoutRequest request) {
        Appointment appointment = appointmentService.getById(request.getAppointmentId());
        if (appointment == null) {
            throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }

        // Resolve gate and area
        Gate gate = null;
        Area area = null;
        if (request.getGateId() != null) {
            gate = gateManageService.getById(request.getGateId());
            area = areaService.getById(gate.getAreaId());
        }

        // Idempotent: already completed
        if (appointment.getStatus() == AppointmentStatusEnum.COMPLETED) {
            AccessLog existingLog = findLatestExitLog(request.getAppointmentId());
            if (existingLog != null) {
                log.info("Idempotent checkout: appointment {} already COMPLETED, returning existing log",
                        request.getAppointmentId());
                return existingLog;
            }
            throw new BizException(ErrorCode.ALREADY_DEPARTED);
        }

        if (appointment.getStatus() != AppointmentStatusEnum.CHECKED_IN) {
            throw new BizException(ErrorCode.NOT_CHECKED_IN);
        }

        // Mark completed
        appointmentService.markCompleted(request.getAppointmentId());

        // Create access log
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser operator = sysUserMapper.findByUsername(username);

        AccessLog accessLog = AccessLog.builder()
                .visitorId(request.getVisitorId())
                .appointmentId(request.getAppointmentId())
                .action(AccessActionEnum.EXIT)
                .gateLocation(request.getGateLocation())
                .gateId(gate != null ? gate.getId() : null)
                .areaId(area != null ? area.getId() : null)
                .result(AccessResultEnum.PASS)
                .operatorId(operator != null ? operator.getId() : null)
                .build();
        accessLogMapper.insert(accessLog);

        // Record trajectory exit
        if (gate != null && area != null) {
            trajectoryService.recordExit(request.getVisitorId(), request.getAppointmentId(),
                    gate.getId(), area.getId());
        }

        log.info("Visitor {} checked out for appointment {}",
                request.getVisitorId(), appointment.getAppointNo());
        return accessLog;
    }

    /**
     * Internal gate pass-through (not entry/exit, for internal area transitions).
     * Does NOT consume pass code uses for internal gates.
     * Checks area authorization and records trajectory.
     */
    @Transactional
    public AccessLog passThroughGate(GateScanRequest request) {
        // Verify pass code (read-only)
        PassCode passCode = passCodeService.verifyForScan(request.getPassCode());

        // Resolve gate and area
        if (request.getGateId() == null) {
            throw new BizException(ErrorCode.GATE_NOT_FOUND, "内部通行需要指定门岗");
        }
        Gate gate = gateManageService.validateGateActive(request.getGateId());
        Area area = areaService.getById(gate.getAreaId());

        Appointment appointment = appointmentService.getById(passCode.getAppointmentId());
        if (appointment == null) {
            throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }
        if (appointment.getStatus() != AppointmentStatusEnum.CHECKED_IN) {
            throw new BizException(ErrorCode.APPOINTMENT_STATUS_INVALID,
                    "访客尚未签到入园，不能通过内部门岗");
        }

        Visitor visitor = visitorService.getById(appointment.getVisitorId());

        // Area authorization check
        if (!areaAuthorizationService.checkAuthorization(appointment.getId(), area.getId())) {
            handleAreaUnauthorized(passCode, visitor, appointment, gate, area);
            throw new BizException(ErrorCode.AREA_UNAUTHORIZED,
                    "访客 " + visitor.getName() + " 未授权进入区域 " + area.getAreaName());
        }

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser operator = sysUserMapper.findByUsername(username);

        AccessLog accessLog = AccessLog.builder()
                .passCodeId(passCode.getId())
                .visitorId(visitor.getId())
                .appointmentId(appointment.getId())
                .action(AccessActionEnum.ENTRY)
                .gateLocation(request.getGateLocation())
                .gateId(gate.getId())
                .areaId(area.getId())
                .result(AccessResultEnum.PASS)
                .operatorId(operator != null ? operator.getId() : null)
                .build();
        accessLogMapper.insert(accessLog);

        // Record trajectory
        trajectoryService.recordPassThrough(visitor.getId(), appointment.getId(),
                gate.getId(), area.getId());

        log.info("Visitor {} passed through gate {} to area {}",
                visitor.getName(), gate.getGateName(), area.getAreaName());
        return accessLog;
    }

    /**
     * Security manual anomaly release.
     */
    @Transactional
    public AccessLog anomalyRelease(AnomalyReleaseRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser security = sysUserMapper.findByUsername(username);

        AnomalyRecord anomaly = AnomalyRecord.builder()
                .visitorId(request.getVisitorId())
                .appointmentId(request.getAppointmentId())
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

    private boolean hasUndepartedRecord(Long visitorId, Long currentAppointmentId) {
        return appointmentService.hasUndepartedAppointment(visitorId, currentAppointmentId);
    }

    /**
     * Handle blacklist denial: create anomaly record, denied access log, and push alert.
     */
    private void handleBlacklistDenial(PassCode passCode, Visitor visitor,
                                        Appointment appointment, Blacklist bl,
                                        String gateLocation, Gate gate, Area area) {
        createAnomaly(visitor.getId(), appointment.getId(),
                AnomalyTypeEnum.BLACKLIST_ATTEMPT,
                "Blacklisted visitor attempted entry: " + bl.getReason(),
                null, gate, area);

        webSocketPushService.pushBlacklistAlert(visitor.getName(), bl.getReason(),
                gate != null ? gate.getGateName() : gateLocation);

        AccessLog deniedLog = AccessLog.builder()
                .passCodeId(passCode.getId())
                .visitorId(visitor.getId())
                .appointmentId(appointment.getId())
                .action(AccessActionEnum.ENTRY)
                .gateLocation(gateLocation)
                .gateId(gate != null ? gate.getId() : null)
                .areaId(area != null ? area.getId() : null)
                .result(AccessResultEnum.DENIED)
                .denyReason("BLACKLISTED: " + bl.getReason())
                .build();
        accessLogMapper.insert(deniedLog);

        log.warn("Blacklist denial: visitor={}, appointment={}, reason={}",
                visitor.getName(), appointment.getAppointNo(), bl.getReason());
    }

    /**
     * Handle unauthorized area access: create anomaly, denied log, push alert.
     */
    private void handleAreaUnauthorized(PassCode passCode, Visitor visitor,
                                         Appointment appointment, Gate gate, Area area) {
        createAnomaly(visitor.getId(), appointment.getId(),
                AnomalyTypeEnum.UNAUTHORIZED_AREA,
                "访客 " + visitor.getName() + " 尝试进入未授权区域 " + area.getAreaName()
                        + " (门岗: " + gate.getGateName() + ")",
                null, gate, area);

        webSocketPushService.pushAreaUnauthorized(
                visitor.getName(), gate.getGateName(), area.getAreaName());

        AccessLog deniedLog = AccessLog.builder()
                .passCodeId(passCode.getId())
                .visitorId(visitor.getId())
                .appointmentId(appointment.getId())
                .action(AccessActionEnum.ENTRY)
                .gateLocation(gate.getGateName())
                .gateId(gate.getId())
                .areaId(area.getId())
                .result(AccessResultEnum.DENIED)
                .denyReason("UNAUTHORIZED_AREA: " + area.getAreaName())
                .build();
        accessLogMapper.insert(deniedLog);

        log.warn("Area unauthorized: visitor={}, gate={}, area={}",
                visitor.getName(), gate.getGateName(), area.getAreaName());
    }

    private void createAnomaly(Long visitorId, Long appointmentId, AnomalyTypeEnum type,
                                String description, Long securityId, Gate gate, Area area) {
        AnomalyRecord record = AnomalyRecord.builder()
                .visitorId(visitorId)
                .appointmentId(appointmentId)
                .anomalyType(type)
                .description(description)
                .securityId(securityId)
                .gateId(gate != null ? gate.getId() : null)
                .areaId(area != null ? area.getId() : null)
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
