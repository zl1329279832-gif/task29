package com.visitor.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.model.dto.AppointmentCreateRequest;
import com.visitor.model.dto.AppointmentRescheduleRequest;
import com.visitor.model.entity.Appointment;
import com.visitor.model.entity.SysUser;
import com.visitor.model.entity.Visitor;
import com.visitor.model.enums.AppointmentStatusEnum;
import com.visitor.model.enums.RoleEnum;
import com.visitor.model.vo.AppointmentVO;
import com.visitor.util.RedisLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentMapper appointmentMapper;
    private final SysUserMapper sysUserMapper;
    private final VisitorService visitorService;
    private final BlacklistService blacklistService;
    private final PassCodeService passCodeService;
    private final AreaAuthorizationService areaAuthorizationService;
    private final WebSocketPushService webSocketPushService;
    private final RedisLock redisLock;

    private static final String APPROVE_LOCK_PREFIX = "appointment:approve:";

    private static final String APPOINTMENT_LOCK_PREFIX = "appointment:state:";

    @Transactional
    public Appointment create(AppointmentCreateRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser host = sysUserMapper.findByUsername(username);

        Visitor visitor = visitorService.getById(request.getVisitorId());

        // Blacklist check
        blacklistService.assertNotBlacklisted(visitor.getName(), visitor.getIdCard(), visitor.getPhone());

        // Duplicate check
        LocalDateTime endTime = request.getExpectedLeave() != null
                ? request.getExpectedLeave()
                : request.getExpectedArrive().plusHours(8);
        int duplicateCount = appointmentMapper.countDuplicate(
                visitor.getId(), host.getId(),
                request.getExpectedArrive(), endTime, null);
        if (duplicateCount > 0) {
            throw new BizException(ErrorCode.DUPLICATE_APPOINTMENT);
        }

        String appointNo = generateAppointNo();

        Appointment appointment = Appointment.builder()
                .appointNo(appointNo)
                .visitorId(visitor.getId())
                .hostId(host.getId())
                .visitType(request.getVisitType())
                .purpose(request.getPurpose())
                .expectedArrive(request.getExpectedArrive())
                .expectedLeave(request.getExpectedLeave())
                .status(AppointmentStatusEnum.PENDING)
                .maxCompanions(request.getMaxCompanions() != null ? request.getMaxCompanions() : 0)
                .build();

        appointmentMapper.insert(appointment);

        webSocketPushService.pushApprovalReminder(appointNo, visitor.getName(), host.getRealName());

        log.info("Created appointment {} for visitor {} hosted by {}", appointNo, visitor.getName(), host.getRealName());
        return appointment;
    }

    public Page<AppointmentVO> list(String status, String keyword, int page, int size) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser user = sysUserMapper.findByUsername(username);

        Long hostId = null;
        if (user.getRole() == RoleEnum.EMPLOYEE) {
            hostId = user.getId();
        }

        Page<AppointmentVO> result = new Page<>(page, size);
        List<AppointmentVO> records = appointmentMapper.selectAppointmentList(hostId, status, keyword);

        int start = (page - 1) * size;
        int end = Math.min(start + size, records.size());
        if (start < records.size()) {
            result.setRecords(records.subList(start, end));
        }
        result.setTotal(records.size());
        return result;
    }

    public AppointmentVO getDetail(Long id) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser user = sysUserMapper.findByUsername(username);

        AppointmentVO vo = appointmentMapper.selectAppointmentDetail(id);
        if (vo == null) {
            throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }

        if (user.getRole() == RoleEnum.EMPLOYEE && !vo.getHostId().equals(user.getId())) {
            throw new BizException(ErrorCode.DATA_ACCESS_DENIED);
        }

        return vo;
    }

    @Transactional
    public void approve(Long appointmentId, Long approverId, String remark) {
        Appointment appointment = appointmentMapper.selectById(appointmentId);
        if (appointment == null) {
            throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }
        if (appointment.getStatus() != AppointmentStatusEnum.PENDING) {
            throw new BizException(ErrorCode.APPOINTMENT_STATUS_INVALID, "only PENDING can be approved");
        }

        if (appointment.getExpectedArrive().isBefore(LocalDateTime.now())) {
            appointment.setStatus(AppointmentStatusEnum.EXPIRED);
            appointmentMapper.updateById(appointment);
            throw new BizException(ErrorCode.APPOINTMENT_EXPIRED);
        }

        // Re-check blacklist at approval time
        Visitor visitor = visitorService.getById(appointment.getVisitorId());
        blacklistService.assertNotBlacklisted(visitor.getName(), visitor.getIdCard(), visitor.getPhone());

        appointment.setStatus(AppointmentStatusEnum.APPROVED);
        appointment.setApprovedBy(approverId);
        appointment.setApprovedAt(LocalDateTime.now());
        appointmentMapper.updateById(appointment);

        // Count pre-existing area authorizations (e.g. from batch import) for dynamic maxUses
        int authorizedAreaCount = areaAuthorizationService.countAuthorizedAreas(appointmentId);
        passCodeService.generateForAppointment(appointment, Math.max(1, authorizedAreaCount));

        SysUser host = sysUserMapper.selectById(appointment.getHostId());
        if (host != null) {
            webSocketPushService.pushApprovalResult(
                    host.getId().toString(), appointment.getAppointNo(), true, remark);
        }

        log.info("Approved appointment {} by approver {}", appointmentId, approverId);
    }

    @Transactional
    public void reject(Long appointmentId, Long approverId, String reason) {
        Appointment appointment = appointmentMapper.selectById(appointmentId);
        if (appointment == null) {
            throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }
        if (appointment.getStatus() != AppointmentStatusEnum.PENDING) {
            throw new BizException(ErrorCode.APPOINTMENT_STATUS_INVALID, "only PENDING can be rejected");
        }

        appointment.setStatus(AppointmentStatusEnum.REJECTED);
        appointment.setApprovedBy(approverId);
        appointment.setApprovedAt(LocalDateTime.now());
        appointment.setRejectReason(reason);
        appointmentMapper.updateById(appointment);

        SysUser host = sysUserMapper.selectById(appointment.getHostId());
        if (host != null) {
            webSocketPushService.pushApprovalResult(
                    host.getId().toString(), appointment.getAppointNo(), false, reason);
        }

        log.info("Rejected appointment {} by approver {}", appointmentId, approverId);
    }

    @Transactional
    public void cancel(Long appointmentId) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser user = sysUserMapper.findByUsername(username);

        Appointment appointment = appointmentMapper.selectById(appointmentId);
        if (appointment == null) {
            throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }

        if (user.getRole() == RoleEnum.EMPLOYEE && !appointment.getHostId().equals(user.getId())) {
            throw new BizException(ErrorCode.DATA_ACCESS_DENIED);
        }

        if (appointment.getStatus() == AppointmentStatusEnum.COMPLETED
                || appointment.getStatus() == AppointmentStatusEnum.CANCELLED) {
            throw new BizException(ErrorCode.APPOINTMENT_STATUS_INVALID, "cannot cancel");
        }

        appointment.setStatus(AppointmentStatusEnum.CANCELLED);
        appointmentMapper.updateById(appointment);

        passCodeService.revokeByAppointmentId(appointmentId);
        areaAuthorizationService.revokeByAppointmentId(appointmentId);

        log.info("Cancelled appointment {}", appointmentId);
    }

    /**
     * Reschedule an appointment:
     * 1. Validates the old appointment can be rescheduled
     * 2. Re-checks blacklist for the visitor
     * 3. Cancels the old appointment and revokes its pass code (old code → invalid)
     * 4. Creates a new PENDING appointment (requires re-approval → new code generated on approval)
     */
    @Transactional
    public Appointment reschedule(Long appointmentId, AppointmentRescheduleRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser user = sysUserMapper.findByUsername(username);

        // Lock the old appointment to prevent concurrent scans during reschedule
        String lockKey = APPOINTMENT_LOCK_PREFIX + appointmentId;
        String lockValue = redisLock.tryLock(lockKey, Duration.ofSeconds(15));
        if (lockValue == null) {
            throw new BizException(ErrorCode.PASS_CODE_DUPLICATE_SCAN, "appointment is being modified");
        }

        try {
            Appointment oldAppointment = appointmentMapper.selectById(appointmentId);
            if (oldAppointment == null) {
                throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
            }

            if (user.getRole() == RoleEnum.EMPLOYEE && !oldAppointment.getHostId().equals(user.getId())) {
                throw new BizException(ErrorCode.DATA_ACCESS_DENIED);
            }

            if (oldAppointment.getStatus() != AppointmentStatusEnum.PENDING
                    && oldAppointment.getStatus() != AppointmentStatusEnum.APPROVED) {
                throw new BizException(ErrorCode.RESCHEDULE_NOT_ALLOWED);
            }

            // Re-check blacklist for the visitor at reschedule time
            Visitor visitor = visitorService.getById(oldAppointment.getVisitorId());
            blacklistService.assertNotBlacklisted(visitor.getName(), visitor.getIdCard(), visitor.getPhone());

            // Cancel old appointment and revoke its pass code (old code becomes invalid)
            oldAppointment.setStatus(AppointmentStatusEnum.CANCELLED);
            appointmentMapper.updateById(oldAppointment);
            passCodeService.revokeByAppointmentId(appointmentId);
            areaAuthorizationService.revokeByAppointmentId(appointmentId);

            // Create new PENDING appointment (requires re-approval)
            Appointment newAppointment = Appointment.builder()
                    .appointNo(generateAppointNo())
                    .visitorId(oldAppointment.getVisitorId())
                    .hostId(oldAppointment.getHostId())
                    .visitType(oldAppointment.getVisitType())
                    .purpose(oldAppointment.getPurpose())
                    .expectedArrive(request.getExpectedArrive())
                    .expectedLeave(request.getExpectedLeave())
                    .status(AppointmentStatusEnum.PENDING)
                    .rescheduleFrom(appointmentId)
                    .maxCompanions(oldAppointment.getMaxCompanions())
                    .build();

            appointmentMapper.insert(newAppointment);

            webSocketPushService.pushApprovalReminder(
                    newAppointment.getAppointNo(), visitor.getName(), user.getRealName());

            log.info("Rescheduled appointment {} -> {} (old code revoked, new code pending approval)",
                    appointmentId, newAppointment.getAppointNo());
            return newAppointment;
        } finally {
            redisLock.unlock(lockKey, lockValue);
        }
    }

    /**
     * Transition appointment to CHECKED_IN.
     * Idempotent: if already CHECKED_IN, this is a no-op (supports retry).
     * If status is not APPROVED and not CHECKED_IN, throws.
     */
    @Transactional
    public void markCheckedIn(Long appointmentId) {
        Appointment appointment = appointmentMapper.selectById(appointmentId);
        if (appointment == null) {
            throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }

        // Idempotent: already checked in is OK (retry scenario)
        if (appointment.getStatus() == AppointmentStatusEnum.CHECKED_IN) {
            log.info("Appointment {} already CHECKED_IN (idempotent no-op)", appointmentId);
            return;
        }

        if (appointment.getStatus() != AppointmentStatusEnum.APPROVED) {
            throw new BizException(ErrorCode.APPOINTMENT_STATUS_INVALID,
                    "only APPROVED can be checked in, current: " + appointment.getStatus());
        }

        appointment.setStatus(AppointmentStatusEnum.CHECKED_IN);
        appointmentMapper.updateById(appointment);
    }

    /**
     * Transition appointment to COMPLETED.
     * Idempotent: if already COMPLETED, this is a no-op.
     */
    @Transactional
    public void markCompleted(Long appointmentId) {
        Appointment appointment = appointmentMapper.selectById(appointmentId);
        if (appointment == null) {
            throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }

        // Idempotent: already completed is OK
        if (appointment.getStatus() == AppointmentStatusEnum.COMPLETED) {
            log.info("Appointment {} already COMPLETED (idempotent no-op)", appointmentId);
            return;
        }

        if (appointment.getStatus() != AppointmentStatusEnum.CHECKED_IN) {
            throw new BizException(ErrorCode.APPOINTMENT_STATUS_INVALID,
                    "only CHECKED_IN can be completed, current: " + appointment.getStatus());
        }

        appointment.setStatus(AppointmentStatusEnum.COMPLETED);
        appointmentMapper.updateById(appointment);
    }

    public int expireOverdueAppointments() {
        List<Appointment> expired = appointmentMapper.selectExpiredPending(LocalDateTime.now());
        for (Appointment appt : expired) {
            appt.setStatus(AppointmentStatusEnum.EXPIRED);
            appointmentMapper.updateById(appt);
        }
        return expired.size();
    }

    public Appointment getById(Long id) {
        return appointmentMapper.selectById(id);
    }

    public List<Appointment> getCheckedInOverdue() {
        return appointmentMapper.selectCheckedInOverdue(LocalDateTime.now());
    }

    /**
     * Check if a visitor has any CHECKED_IN appointment (undeparted) other than the given one.
     * Used by GateService to block re-entry without prior exit.
     */
    public boolean hasUndepartedAppointment(Long visitorId, Long excludeAppointmentId) {
        long count = appointmentMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Appointment>()
                        .eq(Appointment::getVisitorId, visitorId)
                        .eq(Appointment::getStatus, AppointmentStatusEnum.CHECKED_IN)
                        .ne(Appointment::getId, excludeAppointmentId));
        return count > 0;
    }

    private String generateAppointNo() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int random = ThreadLocalRandom.current().nextInt(1000, 9999);
        return "APT" + date + random;
    }
}
