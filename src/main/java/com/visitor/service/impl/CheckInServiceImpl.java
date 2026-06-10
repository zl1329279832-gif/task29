package com.visitor.service.impl;

import com.visitor.common.constant.AppointmentStatus;
import com.visitor.common.exception.BusinessException;
import com.visitor.common.result.PageResult;
import com.visitor.common.util.SecurityUtils;
import com.visitor.dto.request.CheckInRequest;
import com.visitor.dto.request.CheckOutRequest;
import com.visitor.dto.response.CheckInResponse;
import com.visitor.dto.response.PassCodeResponse;
import com.visitor.entity.Appointment;
import com.visitor.entity.CheckInRecord;
import com.visitor.entity.SysUser;
import com.visitor.entity.Visitor;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.CheckInRecordMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.mapper.VisitorMapper;
import com.visitor.service.BlacklistService;
import com.visitor.service.CheckInService;
import com.visitor.service.NotificationService;
import com.visitor.service.PassCodeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CheckInServiceImpl implements CheckInService {

    private final CheckInRecordMapper checkInRecordMapper;
    private final AppointmentMapper appointmentMapper;
    private final VisitorMapper visitorMapper;
    private final SysUserMapper sysUserMapper;
    private final PassCodeService passCodeService;
    private final BlacklistService blacklistService;
    private final NotificationService notificationService;

    public CheckInServiceImpl(CheckInRecordMapper checkInRecordMapper, AppointmentMapper appointmentMapper,
                               VisitorMapper visitorMapper, SysUserMapper sysUserMapper,
                               PassCodeService passCodeService, BlacklistService blacklistService,
                               NotificationService notificationService) {
        this.checkInRecordMapper = checkInRecordMapper;
        this.appointmentMapper = appointmentMapper;
        this.visitorMapper = visitorMapper;
        this.sysUserMapper = sysUserMapper;
        this.passCodeService = passCodeService;
        this.blacklistService = blacklistService;
        this.notificationService = notificationService;
    }

    @Override
    @Transactional
    public CheckInResponse checkIn(CheckInRequest request) {
        // Verify pass code (includes HMAC check, time check, use count check)
        PassCodeResponse passCodeResp = passCodeService.verify(request.getPassCode());

        Appointment appointment = appointmentMapper.findById(passCodeResp.getAppointmentId());
        if (appointment == null) {
            throw new BusinessException("关联预约不存在");
        }

        // Validate appointment status
        AppointmentStatus status = AppointmentStatus.valueOf(appointment.getStatus());
        status.validateTransition(AppointmentStatus.CHECKED_IN);

        Visitor visitor = visitorMapper.findById(appointment.getVisitorId());

        // Check blacklist
        if (visitor != null && blacklistService.isBlacklisted(visitor.getPhone(), visitor.getIdCard())) {
            notificationService.sendAbnormalAlert(visitor.getName(), "黑名单访客尝试签到");
            throw new BusinessException("该访客在黑名单中，禁止通行");
        }

        // Check if already checked in
        CheckInRecord existingRecord = checkInRecordMapper.findActiveByAppointmentId(appointment.getId());
        if (existingRecord != null) {
            throw new BusinessException("该预约已有访客在场，请先签退");
        }

        // Create check-in record
        CheckInRecord record = new CheckInRecord();
        record.setAppointmentId(appointment.getId());
        record.setVisitorId(appointment.getVisitorId());
        record.setPassCodeId(passCodeResp.getId());
        record.setCheckInTime(LocalDateTime.now());
        record.setGateId(request.getGateId());
        record.setStatus("CHECKED_IN");
        checkInRecordMapper.insert(record);

        // Update appointment status
        appointmentMapper.updateStatus(appointment.getId(), AppointmentStatus.CHECKED_IN.name());

        // Notify host
        notificationService.sendVisitorArrival(appointment.getId());

        return toResponse(record, appointment, visitor);
    }

    @Override
    @Transactional
    public CheckInResponse checkOut(CheckOutRequest request) {
        CheckInRecord record = checkInRecordMapper.findById(request.getCheckInRecordId());
        if (record == null) {
            throw new BusinessException("签到记录不存在");
        }
        if (!"CHECKED_IN".equals(record.getStatus())) {
            throw new BusinessException("该记录不在签到状态");
        }

        checkInRecordMapper.updateCheckOut(record.getId(), LocalDateTime.now(),
                request.getGateId(), request.getRemark());
        record.setStatus("CHECKED_OUT");
        record.setCheckOutTime(LocalDateTime.now());

        // Update appointment status
        Appointment appointment = appointmentMapper.findById(record.getAppointmentId());
        if (appointment != null) {
            appointmentMapper.updateStatus(appointment.getId(), AppointmentStatus.CHECKED_OUT.name());
        }

        Visitor visitor = visitorMapper.findById(record.getVisitorId());
        return toResponse(record, appointment, visitor);
    }

    @Override
    public PageResult<CheckInResponse> getCurrentVisitors(int page, int size) {
        int offset = (page - 1) * size;
        List<CheckInRecord> records = checkInRecordMapper.findCurrentVisitors(offset, size);
        long total = checkInRecordMapper.countCurrentVisitors();
        List<CheckInResponse> responses = records.stream().map(r -> {
            Appointment a = appointmentMapper.findById(r.getAppointmentId());
            Visitor v = visitorMapper.findById(r.getVisitorId());
            return toResponse(r, a, v);
        }).collect(Collectors.toList());
        return PageResult.of(responses, total, page, size);
    }

    @Override
    public PageResult<CheckInResponse> getRecords(Long visitorId, Long appointmentId, int page, int size) {
        int offset = (page - 1) * size;
        List<CheckInRecord> records = checkInRecordMapper.findByCondition(visitorId, appointmentId, offset, size);
        long total = checkInRecordMapper.countByCondition(visitorId, appointmentId);
        List<CheckInResponse> responses = records.stream().map(r -> {
            Appointment a = appointmentMapper.findById(r.getAppointmentId());
            Visitor v = visitorMapper.findById(r.getVisitorId());
            return toResponse(r, a, v);
        }).collect(Collectors.toList());
        return PageResult.of(responses, total, page, size);
    }

    private CheckInResponse toResponse(CheckInRecord record, Appointment appointment, Visitor visitor) {
        CheckInResponse response = new CheckInResponse();
        response.setId(record.getId());
        response.setAppointmentId(record.getAppointmentId());
        if (appointment != null) {
            response.setAppointmentNo(appointment.getAppointmentNo());
            SysUser host = sysUserMapper.findById(appointment.getHostUserId());
            if (host != null) response.setHostUserName(host.getRealName());
        }
        if (visitor != null) {
            response.setVisitorName(visitor.getName());
            response.setVisitorPhone(visitor.getPhone());
        }
        response.setCheckInTime(record.getCheckInTime());
        response.setCheckOutTime(record.getCheckOutTime());
        response.setGateId(record.getGateId());
        response.setStatus(record.getStatus());
        return response;
    }
}
