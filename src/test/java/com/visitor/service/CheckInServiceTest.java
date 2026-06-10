package com.visitor.service;

import com.visitor.common.constant.AppointmentStatus;
import com.visitor.common.exception.BusinessException;
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
import com.visitor.service.impl.CheckInServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckInServiceTest {

    @Mock private CheckInRecordMapper checkInRecordMapper;
    @Mock private AppointmentMapper appointmentMapper;
    @Mock private VisitorMapper visitorMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private PassCodeService passCodeService;
    @Mock private BlacklistService blacklistService;
    @Mock private NotificationService notificationService;

    @InjectMocks
    private CheckInServiceImpl checkInService;

    @Test
    void checkIn_success() {
        CheckInRequest request = new CheckInRequest();
        request.setPassCode("valid-code");
        request.setGateId("GATE-01");

        PassCodeResponse passCodeResp = new PassCodeResponse();
        passCodeResp.setId(1L);
        passCodeResp.setAppointmentId(1L);
        when(passCodeService.verify("valid-code")).thenReturn(passCodeResp);

        Appointment appointment = new Appointment();
        appointment.setId(1L);
        appointment.setVisitorId(10L);
        appointment.setHostUserId(2L);
        appointment.setStatus(AppointmentStatus.APPROVED.name());
        appointment.setAppointmentNo("VIS001");
        when(appointmentMapper.findById(1L)).thenReturn(appointment);

        Visitor visitor = new Visitor();
        visitor.setId(10L);
        visitor.setName("张三");
        visitor.setPhone("13800000001");
        when(visitorMapper.findById(10L)).thenReturn(visitor);

        when(blacklistService.isBlacklisted("13800000001", null)).thenReturn(false);
        when(checkInRecordMapper.findActiveByAppointmentId(1L)).thenReturn(null);

        SysUser host = new SysUser();
        host.setId(2L);
        host.setRealName("Host");
        when(sysUserMapper.findById(2L)).thenReturn(host);

        CheckInResponse result = checkInService.checkIn(request);

        assertNotNull(result);
        assertEquals("CHECKED_IN", result.getStatus());
        verify(appointmentMapper).updateStatus(1L, AppointmentStatus.CHECKED_IN.name());
        verify(notificationService).sendVisitorArrival(1L);
    }

    @Test
    void checkIn_blacklisted_throwsException() {
        CheckInRequest request = new CheckInRequest();
        request.setPassCode("valid-code");

        PassCodeResponse passCodeResp = new PassCodeResponse();
        passCodeResp.setId(1L);
        passCodeResp.setAppointmentId(1L);
        when(passCodeService.verify("valid-code")).thenReturn(passCodeResp);

        Appointment appointment = new Appointment();
        appointment.setId(1L);
        appointment.setVisitorId(10L);
        appointment.setStatus(AppointmentStatus.APPROVED.name());
        when(appointmentMapper.findById(1L)).thenReturn(appointment);

        Visitor visitor = new Visitor();
        visitor.setId(10L);
        visitor.setPhone("13800000001");
        visitor.setIdCard("123456");
        when(visitorMapper.findById(10L)).thenReturn(visitor);

        when(blacklistService.isBlacklisted("13800000001", "123456")).thenReturn(true);

        assertThrows(BusinessException.class, () -> checkInService.checkIn(request));
        verify(notificationService).sendAbnormalAlert(any(), any());
    }

    @Test
    void checkIn_alreadyCheckedIn_throwsException() {
        CheckInRequest request = new CheckInRequest();
        request.setPassCode("valid-code");

        PassCodeResponse passCodeResp = new PassCodeResponse();
        passCodeResp.setId(1L);
        passCodeResp.setAppointmentId(1L);
        when(passCodeService.verify("valid-code")).thenReturn(passCodeResp);

        Appointment appointment = new Appointment();
        appointment.setId(1L);
        appointment.setVisitorId(10L);
        appointment.setStatus(AppointmentStatus.APPROVED.name());
        when(appointmentMapper.findById(1L)).thenReturn(appointment);

        Visitor visitor = new Visitor();
        visitor.setId(10L);
        visitor.setPhone("13800000001");
        when(visitorMapper.findById(10L)).thenReturn(visitor);

        when(blacklistService.isBlacklisted(anyString(), any())).thenReturn(false);
        when(checkInRecordMapper.findActiveByAppointmentId(1L)).thenReturn(new CheckInRecord());

        assertThrows(BusinessException.class, () -> checkInService.checkIn(request));
    }

    @Test
    void checkOut_success() {
        CheckOutRequest request = new CheckOutRequest();
        request.setCheckInRecordId(1L);
        request.setGateId("GATE-02");

        CheckInRecord record = new CheckInRecord();
        record.setId(1L);
        record.setAppointmentId(1L);
        record.setVisitorId(10L);
        record.setStatus("CHECKED_IN");
        when(checkInRecordMapper.findById(1L)).thenReturn(record);

        Appointment appointment = new Appointment();
        appointment.setId(1L);
        appointment.setAppointmentNo("VIS001");
        appointment.setHostUserId(2L);
        when(appointmentMapper.findById(1L)).thenReturn(appointment);

        Visitor visitor = new Visitor();
        visitor.setId(10L);
        visitor.setName("张三");
        when(visitorMapper.findById(10L)).thenReturn(visitor);

        SysUser host = new SysUser();
        host.setId(2L);
        host.setRealName("Host");
        when(sysUserMapper.findById(2L)).thenReturn(host);

        CheckInResponse result = checkInService.checkOut(request);

        assertNotNull(result);
        verify(checkInRecordMapper).updateCheckOut(eq(1L), any(LocalDateTime.class), eq("GATE-02"), any());
        verify(appointmentMapper).updateStatus(1L, AppointmentStatus.CHECKED_OUT.name());
    }

    @Test
    void checkOut_notCheckedIn_throwsException() {
        CheckOutRequest request = new CheckOutRequest();
        request.setCheckInRecordId(1L);

        CheckInRecord record = new CheckInRecord();
        record.setId(1L);
        record.setStatus("CHECKED_OUT");
        when(checkInRecordMapper.findById(1L)).thenReturn(record);

        assertThrows(BusinessException.class, () -> checkInService.checkOut(request));
    }
}
