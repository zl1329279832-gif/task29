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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Visitor check-in by scanning QR pass code.
     *
     * Correct order: verify code (no consume) → validate appointment → blacklist recheck
     * → duplicate entry check → idempotent log check → consume code → update state → log → push.
     *
     * If any business check fails after verify(), the scan lock is released without consuming
     * the code, so the visitor can retry later.
     */
    @Transactional
    public AccessLog checkin(GateCheckinRequest request) {
        // 1. Verify pass code signature + status + validity (does NOT consume)
        PassCode passCode = passCodeService.verify(request.getPassCode());

        try {
            // 2. Get appointment and validate status
            Appointment appointment = appointmentService.getById(passCode.getAppointmentId());
            if (appointment == null) {
                throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
            }

            if (appointment.getStatus() != AppointmentStatusEnum.APPROVED) {
                throw new BizException(ErrorCode.APPOINTMENT_STATUS_INVALID, "appointment not in APPROVED state");
            }

            // 3. Blacklist recheck at gate
            Visitor visitor = visitorService.getById(appointment.getVisitorId());
            Blacklist bl = blacklistService.check(visitor.getName(), visitor.getIdCard(), visitor.getPhone());
            if (bl != null) {
                createAnomaly(appointment.getVisitorId(), appointment.getId(),
                        AnomalyTypeEnum.BLACKLIST_ATTEMPT,
                        "Blacklisted visitor attempted entry: " + bl.getReason(), null);

                AccessLog deniedLog = AccessLog.builder()
                        .passCodeId(passCode.getId())
                        .visitorId(visitor.getId())
                        .appointmentId(appointment.getId())
                        .action(AccessActionEnum.ENTRY)
                        .gateLocation(request.getGateLocation())
                        .result(AccessResultEnum.DENIED)
                        .denyReason("BLACKLISTED")
                        .build();
                accessLogMapper.insert(deniedLog);

                // Push blacklist alert after commit
                webSocketPushService.pushAfterCommit(() ->
                        webSocketPushService.pushBlacklistAlert(
                                visitor.getName(), bl.getReason(), request.getGateLocation()));

                throw new BizException(ErrorCode.BLACKLIST_HIT, bl.getReason());
            }

            // 4. Check for undeparted entry — prevent duplicate entry without exit
            int undepartedCount = accessLogMapper.countUndepartedEntry(appointment.getId());
            if (undepartedCount > 0) {
                createAnomaly(appointment.getVisitorId(), appointment.getId(),
                        AnomalyTypeEnum.DUPLICATE_ENTRY,
                        "Visitor attempted re-entry without departure", null);
                throw new BizException(ErrorCode.DUPLICATE_ENTRY);
            }

            // 5. Idempotent check — skip if this exact log already exists
            int existingLogs = accessLogMapper.countExistingPassLog(
                    passCode.getId(), appointment.getId(), AccessActionEnum.ENTRY.name());
            if (existingLogs > 0) {
                log.warn("Duplicate checkin attempt detected for passCode={}, appointment={}",
                        passCode.getId(), appointment.getId());
                throw new BizException(ErrorCode.ALREADY_CHECKED_IN);
            }

            // 6. All checks passed — now consume the pass code
            passCodeService.markUsed(passCode);

            // 7. Mark appointment as checked in
            appointmentService.markCheckedIn(appointment.getId());

            // 8. Increment visitor count
            visitorService.incrementVisitCount(visitor.getId());

            // 9. Create access log
            String username = SecurityContextHolder.getContext().getAuthentication().getName();
            SysUser operator = sysUserMapper.findByUsername(username);

            AccessLog accessLog = AccessLog.builder()
                    .passCodeId(passCode.getId())
                    .visitorId(visitor.getId())
                    .appointmentId(appointment.getId())
                    .action(AccessActionEnum.ENTRY)
                    .gateLocation(request.getGateLocation())
                    .result(AccessResultEnum.PASS)
                    .operatorId(operator != null ? operator.getId() : null)
                    .build();
            accessLogMapper.insert(accessLog);

            // 10. Push arrival notification after transaction commits
            SysUser host = sysUserMapper.selectById(appointment.getHostId());
            if (host != null) {
                String hostId = host.getId().toString();
                String vName = visitor.getName();
                String aptNo = appointment.getAppointNo();
                webSocketPushService.pushAfterCommit(() ->
                        webSocketPushService.pushVisitorArrived(hostId, vName, aptNo));
            }

            log.info("Visitor {} checked in for appointment {}", visitor.getName(), appointment.getAppointNo());
            return accessLog;

        } catch (Exception e) {
            // Release scan lock without consuming on any failure
            passCodeService.releaseScanLock(passCode);
            throw e;
        }
    }

    /**
     * Visitor check-out (departure)
     */
    @Transactional
    public AccessLog checkout(GateCheckoutRequest request) {
        Appointment appointment = appointmentService.getById(request.getAppointmentId());
        if (appointment == null) {
            throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }

        if (appointment.getStatus() == AppointmentStatusEnum.COMPLETED) {
            throw new BizException(ErrorCode.ALREADY_DEPARTED);
        }
        if (appointment.getStatus() != AppointmentStatusEnum.CHECKED_IN) {
            throw new BizException(ErrorCode.NOT_CHECKED_IN);
        }

        // Mark completed
        appointmentService.markCompleted(appointment.getId());

        // Create access log
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser operator = sysUserMapper.findByUsername(username);

        AccessLog accessLog = AccessLog.builder()
                .visitorId(request.getVisitorId())
                .appointmentId(request.getAppointmentId())
                .action(AccessActionEnum.EXIT)
                .gateLocation(request.getGateLocation())
                .result(AccessResultEnum.PASS)
                .operatorId(operator != null ? operator.getId() : null)
                .build();
        accessLogMapper.insert(accessLog);

        log.info("Visitor {} checked out for appointment {}", request.getVisitorId(), appointment.getAppointNo());
        return accessLog;
    }

    /**
     * Security manual anomaly release
     */
    @Transactional
    public AccessLog anomalyRelease(AnomalyReleaseRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser security = sysUserMapper.findByUsername(username);

        // Create anomaly record
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

        // Create access log for the anomaly release
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

        // Push anomaly alert after commit
        Visitor visitor = visitorService.getById(request.getVisitorId());
        String aType = request.getAnomalyType().name();
        String vName = visitor.getName();
        String desc = request.getDescription();
        webSocketPushService.pushAfterCommit(() ->
                webSocketPushService.pushAnomalyAlert(aType, vName, desc));

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

    private void createAnomaly(Long visitorId, Long appointmentId, AnomalyTypeEnum type,
                                String description, Long securityId) {
        AnomalyRecord record = AnomalyRecord.builder()
                .visitorId(visitorId)
                .appointmentId(appointmentId)
                .anomalyType(type)
                .description(description)
                .securityId(securityId)
                .status(AnomalyStatusEnum.OPEN)
                .build();
        anomalyRecordMapper.insert(record);
    }
}
