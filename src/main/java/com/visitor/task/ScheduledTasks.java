package com.visitor.task;

import com.visitor.mapper.SysUserMapper;
import com.visitor.model.entity.Appointment;
import com.visitor.model.entity.SysUser;
import com.visitor.model.entity.Visitor;
import com.visitor.service.AppointmentService;
import com.visitor.service.PassCodeService;
import com.visitor.service.VisitorService;
import com.visitor.service.WebSocketPushService;
import com.visitor.util.RedisLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final StringRedisTemplate stringRedisTemplate;

    private static final String EXPIRE_TASK_LOCK = "visitor:task:expire";
    private static final String UNDEPARTED_TASK_LOCK = "visitor:task:undeparted";
    private static final String UNDEPARTED_WARNED_PREFIX = "visitor:warned:undeparted:";

    /**
     * Every minute: expire overdue pending appointments and expired pass codes.
     * Uses distributed lock to prevent duplicate execution across multiple instances.
     */
    @Scheduled(fixedRate = 60000)
    public void expireOverdueItems() {
        String lockValue = redisLock.tryLock(EXPIRE_TASK_LOCK, Duration.ofSeconds(50));
        if (lockValue == null) {
            return; // Another instance is handling this
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
            redisLock.unlock(EXPIRE_TASK_LOCK, lockValue);
        }
    }

    /**
     * Every 5 minutes: detect checked-in visitors who have not departed past expected leave time.
     * Uses distributed lock and Redis set to avoid pushing duplicate warnings for the same appointment.
     */
    @Scheduled(fixedRate = 300000)
    public void detectUndepartedVisitors() {
        String lockValue = redisLock.tryLock(UNDEPARTED_TASK_LOCK, Duration.ofSeconds(240));
        if (lockValue == null) {
            return;
        }

        try {
            List<Appointment> overdueAppointments = appointmentService.getCheckedInOverdue();
            LocalDateTime now = LocalDateTime.now();

            for (Appointment appt : overdueAppointments) {
                String warnedKey = UNDEPARTED_WARNED_PREFIX + appt.getId();

                // Skip if already warned within the last 30 minutes
                Boolean alreadyWarned = stringRedisTemplate.hasKey(warnedKey);
                if (Boolean.TRUE.equals(alreadyWarned)) {
                    continue;
                }

                Visitor visitor = visitorService.getById(appt.getVisitorId());
                long overdueMinutes = Duration.between(appt.getExpectedLeave(), now).toMinutes();

                log.warn("Undeparted visitor: {} (appointment {}), overdue {} minutes",
                        visitor.getName(), appt.getAppointNo(), overdueMinutes);

                // Push warning to security
                webSocketPushService.pushUndepartedWarning(
                        visitor.getName(), appt.getAppointNo(), overdueMinutes);

                // Mark as warned for 30 minutes to prevent duplicate push
                stringRedisTemplate.opsForValue().set(warnedKey, "1", Duration.ofMinutes(30));
            }
        } catch (Exception e) {
            log.error("Error in detectUndepartedVisitors task", e);
        } finally {
            redisLock.unlock(UNDEPARTED_TASK_LOCK, lockValue);
        }
    }
}
