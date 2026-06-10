package com.visitor.task;

import com.visitor.mapper.SysUserMapper;
import com.visitor.model.entity.Appointment;
import com.visitor.model.entity.SysUser;
import com.visitor.model.entity.Visitor;
import com.visitor.service.AppointmentService;
import com.visitor.service.PassCodeService;
import com.visitor.service.VisitorService;
import com.visitor.service.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTasks {

    private final AppointmentService appointmentService;
    private final PassCodeService passCodeService;
    private final VisitorService visitorService;
    private final WebSocketPushService webSocketPushService;

    /**
     * Every minute: expire overdue pending appointments and expired pass codes
     */
    @Scheduled(fixedRate = 60000)
    public void expireOverdueItems() {
        try {
            int expiredAppointments = appointmentService.expireOverdueAppointments();
            int expiredPassCodes = passCodeService.expirePassCodes();

            if (expiredAppointments > 0 || expiredPassCodes > 0) {
                log.info("Scheduled expiry: {} appointments, {} pass codes expired",
                        expiredAppointments, expiredPassCodes);
            }
        } catch (Exception e) {
            log.error("Error in expireOverdueItems task", e);
        }
    }

    /**
     * Every 5 minutes: detect checked-in visitors who have not departed past expected leave time
     */
    @Scheduled(fixedRate = 300000)
    public void detectUndepartedVisitors() {
        try {
            List<Appointment> overdueAppointments = appointmentService.getCheckedInOverdue();
            LocalDateTime now = LocalDateTime.now();

            for (Appointment appt : overdueAppointments) {
                Visitor visitor = visitorService.getById(appt.getVisitorId());
                long overdueMinutes = Duration.between(appt.getExpectedLeave(), now).toMinutes();

                log.warn("Undeparted visitor: {} (appointment {}), overdue {} minutes",
                        visitor.getName(), appt.getAppointNo(), overdueMinutes);

                // Push warning to security
                webSocketPushService.pushUndepartedWarning(
                        visitor.getName(), appt.getAppointNo(), overdueMinutes);
            }
        } catch (Exception e) {
            log.error("Error in detectUndepartedVisitors task", e);
        }
    }
}
