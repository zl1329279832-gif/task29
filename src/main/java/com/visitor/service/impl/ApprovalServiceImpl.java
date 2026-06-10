package com.visitor.service.impl;

import com.visitor.common.constant.AppointmentStatus;
import com.visitor.common.constant.ApprovalAction;
import com.visitor.common.constant.RoleConstants;
import com.visitor.common.exception.BusinessException;
import com.visitor.common.result.PageResult;
import com.visitor.common.util.SecurityUtils;
import com.visitor.dto.request.ApprovalRequest;
import com.visitor.dto.response.AppointmentResponse;
import com.visitor.dto.response.ApprovalResponse;
import com.visitor.entity.Appointment;
import com.visitor.entity.ApprovalRecord;
import com.visitor.entity.SysUser;
import com.visitor.entity.Visitor;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.ApprovalRecordMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.mapper.VisitorMapper;
import com.visitor.service.ApprovalService;
import com.visitor.service.NotificationService;
import com.visitor.service.PassCodeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ApprovalServiceImpl implements ApprovalService {

    private final AppointmentMapper appointmentMapper;
    private final ApprovalRecordMapper approvalRecordMapper;
    private final SysUserMapper sysUserMapper;
    private final VisitorMapper visitorMapper;
    private final PassCodeService passCodeService;
    private final NotificationService notificationService;

    public ApprovalServiceImpl(AppointmentMapper appointmentMapper, ApprovalRecordMapper approvalRecordMapper,
                                SysUserMapper sysUserMapper, VisitorMapper visitorMapper,
                                PassCodeService passCodeService, NotificationService notificationService) {
        this.appointmentMapper = appointmentMapper;
        this.approvalRecordMapper = approvalRecordMapper;
        this.sysUserMapper = sysUserMapper;
        this.visitorMapper = visitorMapper;
        this.passCodeService = passCodeService;
        this.notificationService = notificationService;
    }

    @Override
    @Transactional
    public void approve(Long appointmentId, ApprovalRequest request) {
        Appointment appointment = appointmentMapper.findById(appointmentId);
        if (appointment == null) throw new BusinessException("预约不存在");

        AppointmentStatus current = AppointmentStatus.valueOf(appointment.getStatus());
        current.validateTransition(AppointmentStatus.APPROVED);

        ApprovalRecord record = new ApprovalRecord();
        record.setAppointmentId(appointmentId);
        record.setApproverId(SecurityUtils.getCurrentUserId());
        record.setAction(ApprovalAction.APPROVE.name());
        record.setComment(request.getComment());
        approvalRecordMapper.insert(record);

        appointmentMapper.updateStatus(appointmentId, AppointmentStatus.APPROVED.name());

        // Generate pass code
        passCodeService.generate(appointmentId);
    }

    @Override
    @Transactional
    public void reject(Long appointmentId, ApprovalRequest request) {
        Appointment appointment = appointmentMapper.findById(appointmentId);
        if (appointment == null) throw new BusinessException("预约不存在");

        AppointmentStatus current = AppointmentStatus.valueOf(appointment.getStatus());
        current.validateTransition(AppointmentStatus.REJECTED);

        ApprovalRecord record = new ApprovalRecord();
        record.setAppointmentId(appointmentId);
        record.setApproverId(SecurityUtils.getCurrentUserId());
        record.setAction(ApprovalAction.REJECT.name());
        record.setComment(request.getComment());
        approvalRecordMapper.insert(record);

        appointmentMapper.updateStatus(appointmentId, AppointmentStatus.REJECTED.name());
    }

    @Override
    public PageResult<AppointmentResponse> getPendingList(int page, int size) {
        Set<String> roles = SecurityUtils.getCurrentRoles();
        String department = null;
        if (roles.contains(RoleConstants.SUPERVISOR) && !roles.contains(RoleConstants.ADMIN) && !roles.contains(RoleConstants.SECURITY)) {
            department = SecurityUtils.getCurrentDepartment();
        }
        int offset = (page - 1) * size;
        List<Appointment> appointments = appointmentMapper.findPendingApproval(department, offset, size);
        long total = appointmentMapper.countPendingApproval(department);

        List<AppointmentResponse> records = appointments.stream().map(a -> {
            AppointmentResponse resp = new AppointmentResponse();
            resp.setId(a.getId());
            resp.setAppointmentNo(a.getAppointmentNo());
            resp.setVisitorId(a.getVisitorId());
            Visitor v = visitorMapper.findById(a.getVisitorId());
            if (v != null) {
                resp.setVisitorName(v.getName());
                resp.setVisitorPhone(v.getPhone());
            }
            resp.setHostUserId(a.getHostUserId());
            SysUser host = sysUserMapper.findById(a.getHostUserId());
            if (host != null) resp.setHostUserName(host.getRealName());
            resp.setVisitReason(a.getVisitReason());
            resp.setVisitStartTime(a.getVisitStartTime());
            resp.setVisitEndTime(a.getVisitEndTime());
            resp.setStatus(a.getStatus());
            resp.setVisitorCount(a.getVisitorCount());
            resp.setCreatedAt(a.getCreatedAt());
            return resp;
        }).collect(Collectors.toList());

        return PageResult.of(records, total, page, size);
    }

    @Override
    public PageResult<ApprovalResponse> getHistory(int page, int size) {
        Long approverId = SecurityUtils.getCurrentUserId();
        int offset = (page - 1) * size;
        List<ApprovalRecord> records = approvalRecordMapper.findByApproverId(approverId, offset, size);
        long total = approvalRecordMapper.countByApproverId(approverId);

        List<ApprovalResponse> responses = records.stream().map(r -> {
            ApprovalResponse resp = new ApprovalResponse();
            resp.setId(r.getId());
            resp.setAppointmentId(r.getAppointmentId());
            Appointment a = appointmentMapper.findById(r.getAppointmentId());
            if (a != null) {
                resp.setAppointmentNo(a.getAppointmentNo());
                Visitor v = visitorMapper.findById(a.getVisitorId());
                if (v != null) resp.setVisitorName(v.getName());
                SysUser host = sysUserMapper.findById(a.getHostUserId());
                if (host != null) resp.setHostUserName(host.getRealName());
                resp.setVisitReason(a.getVisitReason());
                resp.setVisitStartTime(a.getVisitStartTime());
                resp.setVisitEndTime(a.getVisitEndTime());
                resp.setStatus(a.getStatus());
            }
            SysUser approver = sysUserMapper.findById(r.getApproverId());
            if (approver != null) resp.setApproverName(approver.getRealName());
            resp.setAction(r.getAction());
            resp.setComment(r.getComment());
            resp.setCreatedAt(r.getCreatedAt());
            return resp;
        }).collect(Collectors.toList());

        return PageResult.of(responses, total, page, size);
    }
}
