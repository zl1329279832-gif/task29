package com.visitor.service;

import com.visitor.mapper.ApprovalRecordMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.model.dto.ApprovalRequest;
import com.visitor.model.entity.ApprovalRecord;
import com.visitor.model.entity.SysUser;
import com.visitor.model.enums.ApprovalActionEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final AppointmentService appointmentService;
    private final ApprovalRecordMapper approvalRecordMapper;
    private final SysUserMapper sysUserMapper;

    @Transactional
    public void processApproval(Long appointmentId, ApprovalRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser approver = sysUserMapper.findByUsername(username);

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
    }
}
