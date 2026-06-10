package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    private final WebSocketPushService webSocketPushService;
    private final RedisLock redisLock;

    private static final String RESCHEDULE_LOCK_PREFIX = "visitor:reschedule:";

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
                .build();

        appointmentMapper.insert(appointment);

        webSocketPushService.pushAfterCommit(() ->
                webSocketPushService.pushApprovalReminder(appointNo, visitor.getName(), host.getRealName()));

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

        // Recheck blacklist before approval
        Visitor visitor = visitorService.getById(appointment.getVisitorId());
        blacklistService.assertNotBlacklisted(visitor.getName(), visitor.getIdCard(), visitor.getPhone());

        appointment.setStatus(AppointmentStatusEnum.APPROVED);
        appointment.setApprovedBy(approverId);
        appointment.setApprovedAt(LocalDateTime.now());
        appointmentMapper.updateById(appointment);

        passCodeService.generateForAppointment(appointment);

        SysUser host = sysUserMapper.selectById(appointment.getHostId());
        if (host != null) {
            String hostId = host.getId().toString();
            String aptNo = appointment.getAppointNo();
            webSocketPushService.pushAfterCommit(() ->
                    webSocketPushService.pushApprovalResult(hostId, aptNo, true, remark));
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
            String hostId = host.getId().toString();
            String aptNo = appointment.getAppointNo();
            webSocketPushService.pushAfterCommit(() ->
                    webSocketPushService.pushApprovalResult(hostId, aptNo, false, reason));
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

        log.info("Cancelled appointment {}", appointmentId);
    }

    /**
     * Reschedule an appointment: cancel old + revoke old code + blacklist recheck + create new.
     * Uses a distributed lock to prevent concurrent reschedule of the same appointment.
     */
    @Transactional
    public Appointment reschedule(Long appointmentId, AppointmentRescheduleRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser user = sysUserMapper.findByUsername(username);

        // Distributed lock to prevent concurrent reschedule
        String lockKey = RESCHEDULE_LOCK_PREFIX + appointmentId;
        String lockValue = redisLock.tryLock(lockKey, Duration.ofSeconds(15));
        if (lockValue == null) {
            throw new BizException(ErrorCode.APPOINTMENT_STATUS_INVALID, "reschedule in progress, please retry");
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

            // Recheck blacklist before creating the new appointment
            Visitor visitor = visitorService.getById(oldAppointment.getVisitorId());
            blacklistService.assertNotBlacklisted(visitor.getName(), visitor.getIdCard(), visitor.getPhone());

            // Cancel old appointment and revoke its pass code
            oldAppointment.setStatus(AppointmentStatusEnum.CANCELLED);
            appointmentMapper.updateById(oldAppointment);
            passCodeService.revokeByAppointmentId(appointmentId);

            // Create new appointment
            String newAppointNo = generateAppointNo();
            Appointment newAppointment = Appointment.builder()
                    .appointNo(newAppointNo)
                    .visitorId(oldAppointment.getVisitorId())
                    .hostId(oldAppointment.getHostId())
                    .visitType(oldAppointment.getVisitType())
                    .purpose(oldAppointment.getPurpose())
                    .expectedArrive(request.getExpectedArrive())
                    .expectedLeave(request.getExpectedLeave())
                    .status(AppointmentStatusEnum.PENDING)
                    .rescheduleFrom(appointmentId)
                    .build();

            appointmentMapper.insert(newAppointment);

            String vName = visitor.getName();
            String hostName = user.getRealName();
            webSocketPushService.pushAfterCommit(() ->
                    webSocketPushService.pushApprovalReminder(newAppointNo, vName, hostName));

            log.info("Rescheduled appointment {} -> {}", appointmentId, newAppointNo);
            return newAppointment;

        } finally {
            redisLock.unlock(lockKey, lockValue);
        }
    }

    @Transactional
    public void markCheckedIn(Long appointmentId) {
        Appointment appointment = appointmentMapper.selectById(appointmentId);
        if (appointment == null) {
            throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }
        if (appointment.getStatus() != AppointmentStatusEnum.APPROVED) {
            throw new BizException(ErrorCode.APPOINTMENT_STATUS_INVALID);
        }
        appointment.setStatus(AppointmentStatusEnum.CHECKED_IN);
        appointmentMapper.updateById(appointment);
    }

    @Transactional
    public void markCompleted(Long appointmentId) {
        Appointment appointment = appointmentMapper.selectById(appointmentId);
        if (appointment == null) {
            throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }
        if (appointment.getStatus() != AppointmentStatusEnum.CHECKED_IN) {
            throw new BizException(ErrorCode.APPOINTMENT_STATUS_INVALID);
        }
        appointment.setStatus(AppointmentStatusEnum.COMPLETED);
        appointmentMapper.updateById(appointment);
    }

    public int expireOverdueAppointments() {
        List<Appointment> expired = appointmentMapper.selectExpiredPending(LocalDateTime.now());
        for (Appointment appt : expired) {
            appt.setStatus(AppointmentStatusEnum.EXPIRED);
            appointmentMapper.updateById(appt);
            // Also revoke any pass codes for expired appointments
            passCodeService.revokeByAppointmentId(appt.getId());
        }
        return expired.size();
    }

    /**
     * Cancel all PENDING/APPROVED appointments for a visitor and revoke their pass codes.
     * Called when a visitor is added to the blacklist.
     */
    @Transactional
    public int cancelAllForVisitor(Long visitorId) {
        LambdaQueryWrapper<Appointment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Appointment::getVisitorId, visitorId)
                .in(Appointment::getStatus, AppointmentStatusEnum.PENDING, AppointmentStatusEnum.APPROVED);
        List<Appointment> activeAppointments = appointmentMapper.selectList(wrapper);

        for (Appointment appt : activeAppointments) {
            appt.setStatus(AppointmentStatusEnum.CANCELLED);
            appointmentMapper.updateById(appt);
            passCodeService.revokeByAppointmentId(appt.getId());
            log.info("Cancelled appointment {} due to blacklist", appt.getAppointNo());
        }
        return activeAppointments.size();
    }

    public Appointment getById(Long id) {
        return appointmentMapper.selectById(id);
    }

    public List<Appointment> getCheckedInOverdue() {
        return appointmentMapper.selectCheckedInOverdue(LocalDateTime.now());
    }

    private String generateAppointNo() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int random = ThreadLocalRandom.current().nextInt(1000, 9999);
        return "APT" + date + random;
    }
}
