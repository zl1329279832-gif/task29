package com.visitor.service;

import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.ApprovalRecordMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.model.dto.ApprovalRequest;
import com.visitor.model.entity.Appointment;
import com.visitor.model.entity.ApprovalRecord;
import com.visitor.model.entity.SysUser;
import com.visitor.model.enums.AppointmentStatusEnum;
import com.visitor.model.enums.ApprovalActionEnum;
import com.visitor.util.RedisLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final AppointmentService appointmentService;
    private final ApprovalRecordMapper approvalRecordMapper;
    private final SysUserMapper sysUserMapper;
    private final RedisLock redisLock;

    private static final String APPROVAL_LOCK_PREFIX = "approval:process:";

    /**
     * Process an approval (approve or reject).
     * Uses a distributed lock on the appointmentId to prevent concurrent
     * duplicate approvals from multiple admins.
     */
    @Transactional
    public void processApproval(Long appointmentId, ApprovalRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser approver = sysUserMapper.findByUsername(username);

        // Distributed lock to prevent duplicate approval processing
        String lockKey = APPROVAL_LOCK_PREFIX + appointmentId;
        String lockValue = redisLock.tryLock(lockKey, Duration.ofSeconds(15));
        if (lockValue == null) {
            throw new BizException(ErrorCode.APPROVAL_ALREADY_PROCESSED,
                    "该预约正在被其他管理员处理");
        }

        try {
            // Pre-check: appointment must be PENDING before we proceed
            Appointment appointment = appointmentService.getById(appointmentId);
            if (appointment == null) {
                throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
            }
            if (appointment.getStatus() != AppointmentStatusEnum.PENDING) {
                throw new BizException(ErrorCode.APPROVAL_ALREADY_PROCESSED,
                        "预约状态已变更为: " + appointment.getStatus());
            }

            if (request.getAction() == ApprovalActionEnum.APPROVE) {
                appointmentService.approve(appointmentId, approver.getId(), request.getRemark());
            } else {
                appointmentService.reject(appointmentId, approver.getId(), request.getRemark());
            }

            // Save approval record
            ApprovalRecord record = ApprovalRecord.builder()
                    .appointmentId(appointmentId)
                    .approverId(approver.getId())
                    .action(request.getAction())
                    .remark(request.getRemark())
                    .build();
            approvalRecordMapper.insert(record);

            log.info("Approval processed: appointmentId={}, action={}, approver={}",
                    appointmentId, request.getAction(), approver.getUsername());
        } finally {
            redisLock.unlock(lockKey, lockValue);
        }
    }
}
