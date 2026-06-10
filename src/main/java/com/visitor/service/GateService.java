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
     * Visitor check-in by scanning QR pass code
     */
    @Transactional
    public AccessLog checkin(GateCheckinRequest request) {
        // 1. Verify and use the pass code (with distributed lock)
        PassCode passCode = passCodeService.verifyAndUse(request.getPassCode(), "ENTRY");

        // 2. Get appointment and validate
        Appointment appointment = appointmentService.getById(passCode.getAppointmentId());
        if (appointment == null) {
            throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }

        if (appointment.getStatus() != AppointmentStatusEnum.APPROVED) {
            throw new BizException(ErrorCode.APPOINTMENT_STATUS_INVALID, "appointment not in APPROVED state");
        }

        // 3. Check blacklist again at gate
        Visitor visitor = visitorService.getById(appointment.getVisitorId());
        Blacklist bl = blacklistService.check(visitor.getName(), visitor.getIdCard(), visitor.getPhone());
        if (bl != null) {
            // Create anomaly record and alert security
            createAnomaly(appointment.getVisitorId(), appointment.getId(),
                    AnomalyTypeEnum.BLACKLIST_ATTEMPT,
                    "Blacklisted visitor attempted entry: " + bl.getReason(), null);
            webSocketPushService.pushBlacklistAlert(visitor.getName(), bl.getReason(),
                    request.getGateLocation());

            // Log denied access
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
            throw new BizException(ErrorCode.BLACKLIST_HIT, bl.getReason());
        }

        // 4. Mark appointment as checked in
        appointmentService.markCheckedIn(appointment.getId());

        // 5. Increment visitor count
        visitorService.incrementVisitCount(visitor.getId());

        // 6. Create access log
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

        // 7. Push arrival notification to host
        SysUser host = sysUserMapper.selectById(appointment.getHostId());
        if (host != null) {
            webSocketPushService.pushVisitorArrived(
                    host.getId().toString(), visitor.getName(), appointment.getAppointNo());
        }

        log.info("Visitor {} checked in for appointment {}", visitor.getName(), appointment.getAppointNo());
        return accessLog;
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

        // Push anomaly alert
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
