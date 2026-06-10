package com.visitor.task;

import com.visitor.model.entity.Appointment;
import com.visitor.model.entity.Visitor;
import com.visitor.service.AppointmentService;
import com.visitor.service.PassCodeService;
import com.visitor.service.VisitorService;
import com.visitor.service.WebSocketPushService;
import com.visitor.util.RedisLock;
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
    private final RedisLock redisLock;

    private static final String EXPIRE_LOCK_KEY = "scheduled:expireOverdueItems";
    private static final String UNDEPARTED_LOCK_KEY = "scheduled:detectUndepartedVisitors";

    /**
     * Every minute: expire overdue pending appointments and expired pass codes.
     * Distributed lock prevents duplicate execution across multiple instances.
     */
    @Scheduled(fixedRate = 60000)
    public void expireOverdueItems() {
        String lockValue = redisLock.tryLock(EXPIRE_LOCK_KEY, Duration.ofSeconds(55));
        if (lockValue == null) {
            log.debug("Skipping expireOverdueItems: another instance is running");
            return;
        }

        try {
            int expiredAppointments = appointmentService.expireOverdueAppointments();
            int expiredPassCodes = passCodeService.expirePassCodes();

            if (expiredAppointments > 0 || expiredPassCodes > 0) {
                log.info("Scheduled expiry: {} appointments, {} pass codes expired",
                        expiredAppointments, expiredPassCodes);
            }
        } catch (Exception e) {
            log.error("Error in expireOverdueItems task", e);
        } finally {
            redisLock.unlock(EXPIRE_LOCK_KEY, lockValue);
        }
    }

    /**
     * Every 5 minutes: detect checked-in visitors who have not departed past expected leave time.
     * Distributed lock prevents duplicate warnings across instances.
     */
    @Scheduled(fixedRate = 300000)
    public void detectUndepartedVisitors() {
        String lockValue = redisLock.tryLock(UNDEPARTED_LOCK_KEY, Duration.ofSeconds(290));
        if (lockValue == null) {
            log.debug("Skipping detectUndepartedVisitors: another instance is running");
            return;
        }

        try {
            List<Appointment> overdueAppointments = appointmentService.getCheckedInOverdue();
            LocalDateTime now = LocalDateTime.now();

            for (Appointment appt : overdueAppointments) {
                try {
                    Visitor visitor = visitorService.getById(appt.getVisitorId());
                    if (visitor == null) {
                        log.warn("Visitor not found for overdue appointment {}", appt.getAppointNo());
                        continue;
                    }

                    long overdueMinutes = Duration.between(appt.getExpectedLeave(), now).toMinutes();

                    log.warn("Undeparted visitor: {} (appointment {}), overdue {} minutes",
                            visitor.getName(), appt.getAppointNo(), overdueMinutes);

                    webSocketPushService.pushUndepartedWarning(
                            visitor.getName(), appt.getAppointNo(), overdueMinutes);
                } catch (Exception e) {
                    log.error("Error processing undeparted visitor for appointment {}: {}",
                            appt.getAppointNo(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Error in detectUndepartedVisitors task", e);
        } finally {
            redisLock.unlock(UNDEPARTED_LOCK_KEY, lockValue);
        }
    }
}
