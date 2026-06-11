package com.visitor.task;

import com.visitor.mapper.AccessLogMapper;
import com.visitor.mapper.AnomalyRecordMapper;
import com.visitor.model.entity.Appointment;
import com.visitor.model.entity.Visitor;
import com.visitor.model.enums.AccessActionEnum;
import com.visitor.model.enums.AccessResultEnum;
import com.visitor.model.enums.AnomalyStatusEnum;
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

import static org.junit.jupiter.api.Assertions.*;
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
    @Mock private AnomalyRecordMapper anomalyRecordMapper;
    @Mock private AccessLogMapper accessLogMapper;

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
        when(anomalyRecordMapper.insert(any())).thenReturn(1);
        when(accessLogMapper.insert(any())).thenReturn(1);

        scheduledTasks.detectUndepartedVisitors();

        verify(webSocketPushService).pushUndepartedWarning(eq("Li Si"), eq("APT001"), anyLong());
        verify(redisLock).unlock(eq("scheduled:detectUndepartedVisitors"), eq("lock-value"));
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
        when(anomalyRecordMapper.insert(any())).thenReturn(1);
        when(accessLogMapper.insert(any())).thenReturn(1);

        scheduledTasks.detectUndepartedVisitors();

        // Second visitor should still get the warning
        verify(webSocketPushService).pushUndepartedWarning(eq("Wang Wu"), eq("APT002"), anyLong());
    }

    // ── New tests: Anomaly record creation ──────────────────────────────

    @Test
    void testUndepartedVisitor_CreatesAnomalyRecord() {
        Appointment overdueAppt = Appointment.builder()
                .id(1L).appointNo("APT001").visitorId(10L)
                .status(AppointmentStatusEnum.CHECKED_IN)
                .expectedLeave(LocalDateTime.now().minusHours(2))
                .build();
        Visitor visitor = Visitor.builder().id(10L).name("Li Si").build();

        when(redisLock.tryLock(eq("scheduled:detectUndepartedVisitors"), any(Duration.class)))
                .thenReturn("lock-value");
        when(appointmentService.getCheckedInOverdue()).thenReturn(List.of(overdueAppt));
        when(visitorService.getById(10L)).thenReturn(visitor);
        when(anomalyRecordMapper.insert(any())).thenReturn(1);
        when(accessLogMapper.insert(any())).thenReturn(1);

        scheduledTasks.detectUndepartedVisitors();

        // Anomaly record with NO_DEPARTURE type must be created
        verify(anomalyRecordMapper).insert(argThat(record ->
                record.getAnomalyType() == AnomalyTypeEnum.NO_DEPARTURE
                        && record.getVisitorId().equals(10L)
                        && record.getAppointmentId().equals(1L)
                        && record.getStatus() == AnomalyStatusEnum.OPEN
                        && record.getDescription().contains("超时未离场")));
    }

    @Test
    void testUndepartedVisitor_CreatesExitLog_TrajectoryClosure() {
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
        when(anomalyRecordMapper.insert(any())).thenReturn(1);
        when(accessLogMapper.insert(any())).thenReturn(1);

        scheduledTasks.detectUndepartedVisitors();

        // EXIT access log with ANOMALY result for trajectory closure
        verify(accessLogMapper).insert(argThat(log ->
                log.getAction() == AccessActionEnum.EXIT
                        && log.getResult() == AccessResultEnum.ANOMALY
                        && log.getVisitorId().equals(10L)
                        && log.getAppointmentId().equals(1L)
                        && log.getDenyReason().contains("AUTO_DEPARTURE")));
    }

    @Test
    void testUndepartedVisitor_MarksAppointmentCompleted() {
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
        when(anomalyRecordMapper.insert(any())).thenReturn(1);
        when(accessLogMapper.insert(any())).thenReturn(1);

        scheduledTasks.detectUndepartedVisitors();

        // Appointment must be marked COMPLETED for trajectory closure
        verify(appointmentService).markCompleted(1L);
    }

    @Test
    void testUndepartedVisitor_AutoDepartFailure_DoesNotStopProcessing() {
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
        Visitor visitor1 = Visitor.builder().id(10L).name("V1").build();
        Visitor visitor2 = Visitor.builder().id(20L).name("V2").build();

        when(redisLock.tryLock(eq("scheduled:detectUndepartedVisitors"), any(Duration.class)))
                .thenReturn("lock-value");
        when(appointmentService.getCheckedInOverdue()).thenReturn(List.of(appt1, appt2));
        when(visitorService.getById(10L)).thenReturn(visitor1);
        when(visitorService.getById(20L)).thenReturn(visitor2);
        when(anomalyRecordMapper.insert(any())).thenReturn(1);
        when(accessLogMapper.insert(any())).thenReturn(1);
        // First appointment auto-depart fails
        doThrow(new RuntimeException("Cannot complete"))
                .when(appointmentService).markCompleted(1L);

        scheduledTasks.detectUndepartedVisitors();

        // Both anomaly records still created
        verify(anomalyRecordMapper, times(2)).insert(any());
        // Second appointment auto-depart still attempted
        verify(appointmentService).markCompleted(2L);
        // Both warnings pushed
        verify(webSocketPushService).pushUndepartedWarning(eq("V1"), eq("APT001"), anyLong());
        verify(webSocketPushService).pushUndepartedWarning(eq("V2"), eq("APT002"), anyLong());
    }
}
