package com.visitor.service.impl;

import com.visitor.common.constant.AppointmentStatus;
import com.visitor.common.constant.RoleConstants;
import com.visitor.common.exception.BusinessException;
import com.visitor.common.exception.DuplicateAppointmentException;
import com.visitor.common.exception.UnauthorizedException;
import com.visitor.common.result.PageResult;
import com.visitor.common.util.SecurityUtils;
import com.visitor.dto.request.AppointmentCreateRequest;
import com.visitor.dto.request.AppointmentRescheduleRequest;
import com.visitor.dto.request.AppointmentUpdateRequest;
import com.visitor.dto.response.AppointmentResponse;
import com.visitor.entity.Appointment;
import com.visitor.entity.SysUser;
import com.visitor.entity.Visitor;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.PassCodeMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.mapper.VisitorMapper;
import com.visitor.service.AppointmentService;
import com.visitor.service.BlacklistService;
import com.visitor.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AppointmentServiceImpl implements AppointmentService {

    private final AppointmentMapper appointmentMapper;
    private final VisitorMapper visitorMapper;
    private final SysUserMapper sysUserMapper;
    private final PassCodeMapper passCodeMapper;
    private final BlacklistService blacklistService;
    private final NotificationService notificationService;

    public AppointmentServiceImpl(AppointmentMapper appointmentMapper, VisitorMapper visitorMapper,
                                   SysUserMapper sysUserMapper, PassCodeMapper passCodeMapper,
                                   BlacklistService blacklistService, NotificationService notificationService) {
        this.appointmentMapper = appointmentMapper;
        this.visitorMapper = visitorMapper;
        this.sysUserMapper = sysUserMapper;
        this.passCodeMapper = passCodeMapper;
        this.blacklistService = blacklistService;
        this.notificationService = notificationService;
    }

    @Override
    @Transactional
    public AppointmentResponse create(AppointmentCreateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        if (request.getVisitStartTime().isAfter(request.getVisitEndTime())) {
            throw new BusinessException("到访开始时间不能晚于结束时间");
        }
        if (request.getVisitStartTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException("到访开始时间不能早于当前时间");
        }

        // Check blacklist
        if (blacklistService.isBlacklisted(request.getVisitorPhone(), request.getVisitorIdCard())) {
            throw new BusinessException("该访客在黑名单中，无法预约");
        }

        // Find or create visitor
        Visitor visitor = visitorMapper.findByPhone(request.getVisitorPhone());
        if (visitor == null) {
            visitor = new Visitor();
            visitor.setName(request.getVisitorName());
            visitor.setPhone(request.getVisitorPhone());
            visitor.setIdCard(request.getVisitorIdCard());
            visitor.setCompany(request.getVisitorCompany());
            visitorMapper.insert(visitor);
        }

        // Check duplicate
        List<Appointment> duplicates = appointmentMapper.findDuplicate(
                visitor.getId(), request.getVisitStartTime(), request.getVisitEndTime(), null);
        if (!duplicates.isEmpty()) {
            throw new DuplicateAppointmentException("该访客在此时间段已有预约");
        }

        // Create appointment
        Appointment appointment = new Appointment();
        appointment.setAppointmentNo(generateAppointmentNo());
        appointment.setVisitorId(visitor.getId());
        appointment.setHostUserId(currentUserId);
        appointment.setVisitReason(request.getVisitReason());
        appointment.setVisitStartTime(request.getVisitStartTime());
        appointment.setVisitEndTime(request.getVisitEndTime());
        appointment.setStatus(AppointmentStatus.PENDING_APPROVAL.name());
        appointment.setVisitorCount(request.getVisitorCount());
        appointment.setRemark(request.getRemark());
        appointment.setCreatedBy(currentUserId);
        appointmentMapper.insert(appointment);

        // Send approval notification
        notificationService.sendApprovalRemind(appointment.getId());

        return toResponse(appointment, visitor);
    }

    @Override
    public AppointmentResponse getById(Long id) {
        Appointment appointment = appointmentMapper.findById(id);
        if (appointment == null) {
            throw new BusinessException("预约不存在");
        }
        checkViewPermission(appointment);
        Visitor visitor = visitorMapper.findById(appointment.getVisitorId());
        return toResponse(appointment, visitor);
    }

    @Override
    public PageResult<AppointmentResponse> list(String status, int page, int size) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        Set<String> roles = SecurityUtils.getCurrentRoles();
        int offset = (page - 1) * size;

        Long hostUserId = null;
        Long createdBy = null;
        String department = null;

        if (roles.contains(RoleConstants.ADMIN) || roles.contains(RoleConstants.SECURITY)) {
            // Can see all
        } else if (roles.contains(RoleConstants.SUPERVISOR)) {
            department = SecurityUtils.getCurrentDepartment();
        } else {
            createdBy = currentUserId;
        }

        List<Appointment> appointments = appointmentMapper.findByCondition(hostUserId, createdBy, department, status, offset, size);
        long total = appointmentMapper.countByCondition(hostUserId, createdBy, department, status);

        List<AppointmentResponse> records = appointments.stream().map(a -> {
            Visitor visitor = visitorMapper.findById(a.getVisitorId());
            return toResponse(a, visitor);
        }).collect(Collectors.toList());

        return PageResult.of(records, total, page, size);
    }

    @Override
    @Transactional
    public AppointmentResponse update(Long id, AppointmentUpdateRequest request) {
        Appointment appointment = appointmentMapper.findById(id);
        if (appointment == null) {
            throw new BusinessException("预约不存在");
        }
        checkOwnerPermission(appointment);

        AppointmentStatus current = AppointmentStatus.valueOf(appointment.getStatus());
        if (current != AppointmentStatus.PENDING_APPROVAL) {
            throw new BusinessException("只有待审批状态的预约可以修改");
        }

        appointment.setVisitReason(request.getVisitReason());
        appointment.setVisitStartTime(request.getVisitStartTime());
        appointment.setVisitEndTime(request.getVisitEndTime());
        if (request.getVisitorCount() != null) {
            appointment.setVisitorCount(request.getVisitorCount());
        }
        appointment.setRemark(request.getRemark());
        appointmentMapper.update(appointment);

        Visitor visitor = visitorMapper.findById(appointment.getVisitorId());
        return toResponse(appointment, visitor);
    }

    @Override
    @Transactional
    public void cancel(Long id) {
        Appointment appointment = appointmentMapper.findById(id);
        if (appointment == null) {
            throw new BusinessException("预约不存在");
        }
        checkOwnerPermission(appointment);

        AppointmentStatus current = AppointmentStatus.valueOf(appointment.getStatus());
        current.validateTransition(AppointmentStatus.CANCELLED);

        appointmentMapper.updateStatus(id, AppointmentStatus.CANCELLED.name());
        passCodeMapper.expireByAppointmentId(id);
    }

    @Override
    @Transactional
    public AppointmentResponse reschedule(Long id, AppointmentRescheduleRequest request) {
        Appointment original = appointmentMapper.findById(id);
        if (original == null) {
            throw new BusinessException("预约不存在");
        }
        checkOwnerPermission(original);

        AppointmentStatus current = AppointmentStatus.valueOf(original.getStatus());
        current.validateTransition(AppointmentStatus.RESCHEDULED);

        if (request.getNewVisitStartTime().isAfter(request.getNewVisitEndTime())) {
            throw new BusinessException("新到访开始时间不能晚于结束时间");
        }

        // Mark original as rescheduled
        appointmentMapper.updateStatus(id, AppointmentStatus.RESCHEDULED.name());
        passCodeMapper.expireByAppointmentId(id);

        // Create new appointment
        Appointment newAppointment = new Appointment();
        newAppointment.setAppointmentNo(generateAppointmentNo());
        newAppointment.setVisitorId(original.getVisitorId());
        newAppointment.setHostUserId(original.getHostUserId());
        newAppointment.setVisitReason(original.getVisitReason());
        newAppointment.setVisitStartTime(request.getNewVisitStartTime());
        newAppointment.setVisitEndTime(request.getNewVisitEndTime());
        newAppointment.setStatus(AppointmentStatus.PENDING_APPROVAL.name());
        newAppointment.setVisitorCount(original.getVisitorCount());
        newAppointment.setRemark(request.getRemark() != null ? request.getRemark() : original.getRemark());
        newAppointment.setCreatedBy(SecurityUtils.getCurrentUserId());
        appointmentMapper.insert(newAppointment);

        notificationService.sendApprovalRemind(newAppointment.getId());

        Visitor visitor = visitorMapper.findById(newAppointment.getVisitorId());
        return toResponse(newAppointment, visitor);
    }

    @Override
    public boolean checkDuplicate(String visitorPhone, String visitStartTime, String visitEndTime) {
        Visitor visitor = visitorMapper.findByPhone(visitorPhone);
        if (visitor == null) return false;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        List<Appointment> duplicates = appointmentMapper.findDuplicate(
                visitor.getId(),
                LocalDateTime.parse(visitStartTime, formatter),
                LocalDateTime.parse(visitEndTime, formatter),
                null);
        return !duplicates.isEmpty();
    }

    private void checkViewPermission(Appointment appointment) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        Set<String> roles = SecurityUtils.getCurrentRoles();

        if (roles.contains(RoleConstants.ADMIN) || roles.contains(RoleConstants.SECURITY)) return;
        if (roles.contains(RoleConstants.SUPERVISOR)) {
            String userDept = SecurityUtils.getCurrentDepartment();
            String hostDept = sysUserMapper.findDepartmentByUserId(appointment.getHostUserId());
            if (userDept != null && userDept.equals(hostDept)) return;
        }
        if (appointment.getHostUserId().equals(currentUserId) || appointment.getCreatedBy().equals(currentUserId)) return;
        throw new UnauthorizedException("无权查看该预约记录");
    }

    private void checkOwnerPermission(Appointment appointment) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        Set<String> roles = SecurityUtils.getCurrentRoles();
        if (roles.contains(RoleConstants.ADMIN)) return;
        if (!appointment.getCreatedBy().equals(currentUserId) && !appointment.getHostUserId().equals(currentUserId)) {
            throw new UnauthorizedException("无权操作该预约");
        }
    }

    private AppointmentResponse toResponse(Appointment appointment, Visitor visitor) {
        AppointmentResponse response = new AppointmentResponse();
        response.setId(appointment.getId());
        response.setAppointmentNo(appointment.getAppointmentNo());
        response.setVisitorId(appointment.getVisitorId());
        if (visitor != null) {
            response.setVisitorName(visitor.getName());
            response.setVisitorPhone(visitor.getPhone());
            response.setVisitorCompany(visitor.getCompany());
        }
        response.setHostUserId(appointment.getHostUserId());
        SysUser hostUser = sysUserMapper.findById(appointment.getHostUserId());
        if (hostUser != null) {
            response.setHostUserName(hostUser.getRealName());
        }
        response.setVisitReason(appointment.getVisitReason());
        response.setVisitStartTime(appointment.getVisitStartTime());
        response.setVisitEndTime(appointment.getVisitEndTime());
        response.setStatus(appointment.getStatus());
        response.setVisitorCount(appointment.getVisitorCount());
        response.setRemark(appointment.getRemark());
        response.setCreatedAt(appointment.getCreatedAt());
        return response;
    }

    private String generateAppointmentNo() {
        return "VIS" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}
