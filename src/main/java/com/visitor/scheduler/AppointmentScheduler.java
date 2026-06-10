package com.visitor.scheduler;

import com.visitor.common.constant.AppointmentStatus;
import com.visitor.common.constant.PassCodeStatus;
import com.visitor.common.constant.RedisKeyConstants;
import com.visitor.entity.Appointment;
import com.visitor.entity.PassCode;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.CheckInRecordMapper;
import com.visitor.mapper.PassCodeMapper;
import com.visitor.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class AppointmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentScheduler.class);

    private final AppointmentMapper appointmentMapper;
    private final PassCodeMapper passCodeMapper;
    private final CheckInRecordMapper checkInRecordMapper;
    private final StringRedisTemplate redisTemplate;
    private final NotificationService notificationService;

    public AppointmentScheduler(AppointmentMapper appointmentMapper, PassCodeMapper passCodeMapper,
                                 CheckInRecordMapper checkInRecordMapper, StringRedisTemplate redisTemplate,
                                 NotificationService notificationService) {
        this.appointmentMapper = appointmentMapper;
        this.passCodeMapper = passCodeMapper;
        this.checkInRecordMapper = checkInRecordMapper;
        this.redisTemplate = redisTemplate;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void handleExpiredAppointments() {
        log.debug("Running expired appointment check...");
        LocalDateTime now = LocalDateTime.now();
        List<Appointment> expiredList = appointmentMapper.findExpiredApproved(now);

        for (Appointment appointment : expiredList) {
            try {
                appointmentMapper.updateStatus(appointment.getId(), AppointmentStatus.EXPIRED.name());
                passCodeMapper.expireByAppointmentId(appointment.getId());

                // Clean Redis cache for pass codes
                PassCode passCode = passCodeMapper.findActiveByAppointmentId(appointment.getId());
                if (passCode != null) {
                    redisTemplate.delete(RedisKeyConstants.PASS_CODE_PREFIX + passCode.getCode());
                }

                notificationService.sendAppointmentExpired(appointment.getId());
                log.info("Expired appointment: {}", appointment.getAppointmentNo());
            } catch (Exception e) {
                log.error("Failed to expire appointment {}: {}", appointment.getId(), e.getMessage());
            }
        }

        // Also expire pass codes directly
        List<PassCode> expiredPassCodes = passCodeMapper.findExpiredActive(now);
        for (PassCode pc : expiredPassCodes) {
            try {
                passCodeMapper.updateStatus(pc.getId(), PassCodeStatus.EXPIRED.name());
                redisTemplate.delete(RedisKeyConstants.PASS_CODE_PREFIX + pc.getCode());
            } catch (Exception e) {
                log.error("Failed to expire pass code {}: {}", pc.getId(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedRate = 600000) // Every 10 minutes
    public void handleOvertimeVisitors() {
        log.debug("Running overtime visitor check...");
        LocalDateTime deadline = LocalDateTime.now().minusHours(2);
        List<Appointment> overtimeList = appointmentMapper.findOvertimeCheckedIn(deadline);

        for (Appointment appointment : overtimeList) {
            try {
                notificationService.sendAbnormalAlert(
                        "预约编号: " + appointment.getAppointmentNo(),
                        "访客超时未离场，已超过预约结束时间2小时"
                );
                log.warn("Overtime visitor detected: appointment={}", appointment.getAppointmentNo());
            } catch (Exception e) {
                log.error("Failed to handle overtime visitor for appointment {}: {}", appointment.getId(), e.getMessage());
            }
        }
    }
}
