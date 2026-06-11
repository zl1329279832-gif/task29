package com.visitor.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.visitor.mapper.AnomalyRecordMapper;
import com.visitor.model.entity.AnomalyRecord;
import com.visitor.model.entity.Appointment;
import com.visitor.model.entity.Visitor;
import com.visitor.model.enums.AnomalyTypeEnum;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
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
    @Mock private AnomalyRecordMapper anomalyRecordMapper;
    @Mock private RedisLock redisLock;

    // ── Expire overdue items ────────────────────────────────────────────

    @Test
    void testExpireOverdueItems_Success() {
        when(redisLock.tryLock(eq("scheduled:expireOverdueItems"), any(Duration.class)))
                .thenReturn("lock-value");
        when(appointmentService.expireOverdueAppointments()).thenReturn(3);
        when(passCodeService.expirePassCodes()).thenReturn(5);

        scheduledTasks.expireOverdueItems();

        verify(appointmentService).expireOverdueAppointments();
        verify(passCodeService).expirePassCodes();
        verify(redisLock).unlock(eq("scheduled:expireOverdueItems"), eq("lock-value"));
    }

    @Test
    void testExpireOverdueItems_SkippedWhenLocked() {
        when(redisLock.tryLock(eq("scheduled:expireOverdueItems"), any(Duration.class)))
                .thenReturn(null);

        scheduledTasks.expireOverdueItems();

        verify(appointmentService, never()).expireOverdueAppointments();
        verify(passCodeService, never()).expirePassCodes();
    }

    @Test
    void testExpireOverdueItems_LockReleasedOnException() {
        when(redisLock.tryLock(eq("scheduled:expireOverdueItems"), any(Duration.class)))
                .thenReturn("lock-value");
        when(appointmentService.expireOverdueAppointments())
                .thenThrow(new RuntimeException("DB error"));

        scheduledTasks.expireOverdueItems();

        verify(redisLock).unlock(eq("scheduled:expireOverdueItems"), eq("lock-value"));
    }

    // ── Detect undeparted visitors ──────────────────────────────────────

    @Test
    void testDetectUndepartedVisitors_Success() {
        Appointment overdueAppt = Appointment.builder()
                .id(1L).appointNo("APT001").visitorId(10L)
                .status(AppointmentStatusEnum.CHECKED_IN)
                .expectedLeave(LocalDateTime.now().minusHours(1))
                .build();
        Visitor visitor = Visitor.builder().id(10L).name("Li Si").build();

        when(redisLock.tryLock(eq("scheduled:detectUndepartedVisitors"), any(Duration.class)))
                .thenReturn("lock-value");
        when(appointmentService.getCheckedInOverdue()).thenReturn(List.of(overdueAppt));
        when(visitorService.getById(10L)).thenReturn(visitor);
        when(anomalyRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(anomalyRecordMapper.insert(any())).thenReturn(1);

        scheduledTasks.detectUndepartedVisitors();

        verify(webSocketPushService).pushUndepartedWarning(eq("Li Si"), eq("APT001"), anyLong());
        // Anomaly record should be created for overstay
        verify(anomalyRecordMapper).insert(argThat(record ->
                record.getAnomalyType() == AnomalyTypeEnum.OVERSTAY
                        && record.getVisitorId().equals(10L)
                        && record.getAppointmentId().equals(1L)));
        verify(redisLock).unlock(eq("scheduled:detectUndepartedVisitors"), eq("lock-value"));
    }

    @Test
    void testDetectUndepartedVisitors_DedupExistingAnomaly() {
        Appointment overdueAppt = Appointment.builder()
                .id(1L).appointNo("APT001").visitorId(10L)
                .status(AppointmentStatusEnum.CHECKED_IN)
                .expectedLeave(LocalDateTime.now().minusHours(1))
                .build();
        Visitor visitor = Visitor.builder().id(10L).name("Li Si").build();

        when(redisLock.tryLock(eq("scheduled:detectUndepartedVisitors"), any(Duration.class)))
                .thenReturn("lock-value");
        when(appointmentService.getCheckedInOverdue()).thenReturn(List.of(overdueAppt));
        when(visitorService.getById(10L)).thenReturn(visitor);
        // Simulate existing OPEN anomaly record — dedup should skip insert
        when(anomalyRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        scheduledTasks.detectUndepartedVisitors();

        // Warning is still pushed (repeated reminders are OK)
        verify(webSocketPushService).pushUndepartedWarning(eq("Li Si"), eq("APT001"), anyLong());
        // But anomaly record should NOT be duplicated
        verify(anomalyRecordMapper, never()).insert(any());
    }

    @Test
    void testDetectUndepartedVisitors_SkippedWhenLocked() {
        when(redisLock.tryLock(eq("scheduled:detectUndepartedVisitors"), any(Duration.class)))
                .thenReturn(null);

        scheduledTasks.detectUndepartedVisitors();

        verify(appointmentService, never()).getCheckedInOverdue();
    }

    @Test
    void testDetectUndepartedVisitors_NoOverdue() {
        when(redisLock.tryLock(eq("scheduled:detectUndepartedVisitors"), any(Duration.class)))
                .thenReturn("lock-value");
        when(appointmentService.getCheckedInOverdue()).thenReturn(Collections.emptyList());

        scheduledTasks.detectUndepartedVisitors();

        verify(webSocketPushService, never()).pushUndepartedWarning(anyString(), anyString(), anyLong());
    }

    @Test
    void testDetectUndepartedVisitors_IndividualErrorDoesNotStopOthers() {
        Appointment appt1 = Appointment.builder()
                .id(1L).appointNo("APT001").visitorId(10L)
                .status(AppointmentStatusEnum.CHECKED_IN)
                .expectedLeave(LocalDateTime.now().minusHours(1))
                .build();
        Appointment appt2 = Appointment.builder()
                .id(2L).appointNo("APT002").visitorId(20L)
                .status(AppointmentStatusEnum.CHECKED_IN)
                .expectedLeave(LocalDateTime.now().minusHours(2))
                .build();
        Visitor visitor2 = Visitor.builder().id(20L).name("Wang Wu").build();

        when(redisLock.tryLock(eq("scheduled:detectUndepartedVisitors"), any(Duration.class)))
                .thenReturn("lock-value");
        when(appointmentService.getCheckedInOverdue()).thenReturn(List.of(appt1, appt2));
        // First visitor lookup throws
        when(visitorService.getById(10L)).thenThrow(new RuntimeException("DB error"));
        // Second visitor lookup succeeds
        when(visitorService.getById(20L)).thenReturn(visitor2);
        when(anomalyRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(anomalyRecordMapper.insert(any())).thenReturn(1);

        scheduledTasks.detectUndepartedVisitors();

        // Second visitor should still get the warning and anomaly record
        verify(webSocketPushService).pushUndepartedWarning(eq("Wang Wu"), eq("APT002"), anyLong());
        verify(anomalyRecordMapper).insert(argThat(record ->
                record.getVisitorId().equals(20L)));
    }
}
