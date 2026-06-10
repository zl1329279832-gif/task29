package com.visitor.task;

import com.visitor.model.entity.Appointment;
import com.visitor.model.entity.Visitor;
import com.visitor.model.enums.AppointmentStatusEnum;
import com.visitor.service.AppointmentService;
import com.visitor.service.PassCodeService;
import com.visitor.service.VisitorService;
import com.visitor.service.WebSocketPushService;
import com.visitor.util.RedisLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledTasksTest {

    @InjectMocks
    private ScheduledTasks scheduledTasks;

    @Mock private AppointmentService appointmentService;
    @Mock private PassCodeService passCodeService;
    @Mock private VisitorService visitorService;
    @Mock private WebSocketPushService webSocketPushService;
    @Mock private RedisLock redisLock;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @Test
    void testExpireOverdueItems_WithLock() {
        when(redisLock.tryLock(anyString(), any())).thenReturn("lock-value");
        when(appointmentService.expireOverdueAppointments()).thenReturn(2);
        when(passCodeService.expirePassCodes()).thenReturn(1);

        scheduledTasks.expireOverdueItems();

        verify(appointmentService).expireOverdueAppointments();
        verify(passCodeService).expirePassCodes();
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    @Test
    void testExpireOverdueItems_LockFailed_Skipped() {
        when(redisLock.tryLock(anyString(), any())).thenReturn(null);

        scheduledTasks.expireOverdueItems();

        // Should skip — no service calls
        verify(appointmentService, never()).expireOverdueAppointments();
        verify(passCodeService, never()).expirePassCodes();
    }

    @Test
    void testDetectUndeparted_FirstWarning() {
        Appointment appt = Appointment.builder()
                .id(1L).appointNo("APT001").visitorId(10L)
                .status(AppointmentStatusEnum.CHECKED_IN)
                .expectedLeave(LocalDateTime.now().minusMinutes(30))
                .build();
        Visitor visitor = Visitor.builder().id(10L).name("Li Si").build();

        when(redisLock.tryLock(anyString(), any())).thenReturn("lock-value");
        when(appointmentService.getCheckedInOverdue()).thenReturn(List.of(appt));
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
        when(visitorService.getById(10L)).thenReturn(visitor);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        scheduledTasks.detectUndepartedVisitors();

        verify(webSocketPushService).pushUndepartedWarning(eq("Li Si"), eq("APT001"), anyLong());
        // Should mark as warned in Redis
        verify(valueOperations).set(anyString(), eq("1"), any());
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    @Test
    void testDetectUndeparted_AlreadyWarned_Skipped() {
        Appointment appt = Appointment.builder()
                .id(1L).appointNo("APT001").visitorId(10L)
                .status(AppointmentStatusEnum.CHECKED_IN)
                .expectedLeave(LocalDateTime.now().minusMinutes(30))
                .build();

        when(redisLock.tryLock(anyString(), any())).thenReturn("lock-value");
        when(appointmentService.getCheckedInOverdue()).thenReturn(List.of(appt));
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(true); // already warned

        scheduledTasks.detectUndepartedVisitors();

        // Should NOT push again
        verify(webSocketPushService, never()).pushUndepartedWarning(anyString(), anyString(), anyLong());
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    @Test
    void testDetectUndeparted_LockFailed_Skipped() {
        when(redisLock.tryLock(anyString(), any())).thenReturn(null);

        scheduledTasks.detectUndepartedVisitors();

        verify(appointmentService, never()).getCheckedInOverdue();
    }
}
