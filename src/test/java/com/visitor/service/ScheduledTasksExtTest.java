package com.visitor.service;

import com.visitor.service.AreaAuthorizationService;
import com.visitor.service.AppointmentService;
import com.visitor.service.PassCodeService;
import com.visitor.service.TrajectoryService;
import com.visitor.service.VisitorService;
import com.visitor.service.WebSocketPushService;
import com.visitor.task.ScheduledTasks;
import com.visitor.util.RedisLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledTasksExtTest {

    @InjectMocks
    private ScheduledTasks scheduledTasks;

    @Mock private AppointmentService appointmentService;
    @Mock private PassCodeService passCodeService;
    @Mock private VisitorService visitorService;
    @Mock private WebSocketPushService webSocketPushService;
    @Mock private TrajectoryService trajectoryService;
    @Mock private AreaAuthorizationService areaAuthorizationService;
    @Mock private RedisLock redisLock;

    // ── Detect overtime area stay ──────────────────────────────────────

    @Test
    void testDetectOvertimeAreaStay_Success() {
        when(redisLock.tryLock(eq("scheduled:detectOvertimeAreaStay"), any(Duration.class)))
                .thenReturn("lock-value");
        when(trajectoryService.detectOvertimeStay()).thenReturn(3);

        scheduledTasks.detectOvertimeAreaStay();

        verify(trajectoryService).detectOvertimeStay();
        verify(redisLock).unlock(eq("scheduled:detectOvertimeAreaStay"), eq("lock-value"));
    }

    @Test
    void testDetectOvertimeAreaStay_LockFailed() {
        when(redisLock.tryLock(eq("scheduled:detectOvertimeAreaStay"), any(Duration.class)))
                .thenReturn(null);

        scheduledTasks.detectOvertimeAreaStay();

        verify(trajectoryService, never()).detectOvertimeStay();
    }

    // ── Expire area authorizations ─────────────────────────────────────

    @Test
    void testExpireAreaAuthorizations_Success() {
        when(redisLock.tryLock(eq("scheduled:expireAreaAuthorizations"), any(Duration.class)))
                .thenReturn("lock-value");
        when(areaAuthorizationService.expireAuthorizations()).thenReturn(5);

        scheduledTasks.expireAreaAuthorizations();

        verify(areaAuthorizationService).expireAuthorizations();
        verify(redisLock).unlock(eq("scheduled:expireAreaAuthorizations"), eq("lock-value"));
    }

    @Test
    void testExpireAreaAuthorizations_ExceptionReleasesLock() {
        when(redisLock.tryLock(eq("scheduled:expireAreaAuthorizations"), any(Duration.class)))
                .thenReturn("lock-value");
        when(areaAuthorizationService.expireAuthorizations())
                .thenThrow(new RuntimeException("DB error"));

        scheduledTasks.expireAreaAuthorizations();

        verify(redisLock).unlock(eq("scheduled:expireAreaAuthorizations"), eq("lock-value"));
    }
}
