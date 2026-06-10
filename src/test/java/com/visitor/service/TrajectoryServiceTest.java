package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.visitor.mapper.AnomalyRecordMapper;
import com.visitor.mapper.VisitorTrajectoryMapper;
import com.visitor.model.entity.AnomalyRecord;
import com.visitor.model.entity.VisitorTrajectory;
import com.visitor.model.enums.AnomalyStatusEnum;
import com.visitor.model.enums.AnomalyTypeEnum;
import com.visitor.model.enums.DepartureStatusEnum;
import com.visitor.model.enums.TrajectoryActionEnum;
import com.visitor.model.vo.TrajectoryVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrajectoryServiceTest {

    @InjectMocks
    private TrajectoryService trajectoryService;

    @Mock
    private VisitorTrajectoryMapper trajectoryMapper;

    @Mock
    private AnomalyRecordMapper anomalyRecordMapper;

    @Mock
    private WebSocketPushService webSocketPushService;

    @Test
    void testRecordEntry_Success() {
        when(trajectoryMapper.insert(any(VisitorTrajectory.class))).thenReturn(1);

        VisitorTrajectory result = trajectoryService.recordEntry(1L, 100L, 10L, 20L);

        assertNotNull(result);
        assertEquals(1L, result.getVisitorId());
        assertEquals(100L, result.getAppointmentId());
        assertEquals(10L, result.getGateId());
        assertEquals(20L, result.getAreaId());
        assertEquals(TrajectoryActionEnum.ENTER_AREA, result.getAction());
        assertEquals(DepartureStatusEnum.IN_AREA, result.getDepartureStatus());
        assertNotNull(result.getRecordedAt());

        verify(trajectoryMapper).insert(any(VisitorTrajectory.class));
    }

    @Test
    void testRecordExit_WithEntry_CalculatesDuration() {
        VisitorTrajectory entryRecord = VisitorTrajectory.builder()
                .id(1L)
                .visitorId(1L)
                .appointmentId(100L)
                .gateId(10L)
                .areaId(20L)
                .action(TrajectoryActionEnum.ENTER_AREA)
                .recordedAt(LocalDateTime.now().minusMinutes(45))
                .departureStatus(DepartureStatusEnum.IN_AREA)
                .build();

        when(trajectoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entryRecord);
        when(trajectoryMapper.updateById(any(VisitorTrajectory.class))).thenReturn(1);
        when(trajectoryMapper.insert(any(VisitorTrajectory.class))).thenReturn(1);

        VisitorTrajectory result = trajectoryService.recordExit(1L, 100L, 10L, 20L);

        // Verify entry record was updated to DEPARTED
        assertEquals(DepartureStatusEnum.DEPARTED, entryRecord.getDepartureStatus());
        assertNotNull(entryRecord.getStayDurationMinutes());
        verify(trajectoryMapper).updateById(entryRecord);

        // Verify exit record
        assertNotNull(result);
        assertEquals(TrajectoryActionEnum.EXIT_AREA, result.getAction());
        assertEquals(DepartureStatusEnum.DEPARTED, result.getDepartureStatus());
        assertNotNull(result.getStayDurationMinutes());

        verify(trajectoryMapper).insert(any(VisitorTrajectory.class));
    }

    @Test
    void testRecordExit_NoEntry_NullDuration() {
        when(trajectoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(trajectoryMapper.insert(any(VisitorTrajectory.class))).thenReturn(1);

        VisitorTrajectory result = trajectoryService.recordExit(1L, 100L, 10L, 20L);

        assertNotNull(result);
        assertEquals(TrajectoryActionEnum.EXIT_AREA, result.getAction());
        assertEquals(DepartureStatusEnum.DEPARTED, result.getDepartureStatus());
        assertNull(result.getStayDurationMinutes());

        // Entry record was not found, so updateById should not be called
        verify(trajectoryMapper, never()).updateById(any());
        verify(trajectoryMapper).insert(any(VisitorTrajectory.class));
    }

    @Test
    void testRecordPassThrough_Success() {
        when(trajectoryMapper.insert(any(VisitorTrajectory.class))).thenReturn(1);

        VisitorTrajectory result = trajectoryService.recordPassThrough(1L, 100L, 10L, 20L);

        assertNotNull(result);
        assertEquals(1L, result.getVisitorId());
        assertEquals(100L, result.getAppointmentId());
        assertEquals(10L, result.getGateId());
        assertEquals(20L, result.getAreaId());
        assertEquals(TrajectoryActionEnum.PASS_GATE, result.getAction());
        assertEquals(DepartureStatusEnum.IN_AREA, result.getDepartureStatus());
        assertNotNull(result.getRecordedAt());

        verify(trajectoryMapper).insert(any(VisitorTrajectory.class));
    }

    @Test
    void testDetectOvertimeStay_AnomalyCreated() {
        VisitorTrajectory overtimeTraj = VisitorTrajectory.builder()
                .id(1L)
                .visitorId(5L)
                .appointmentId(200L)
                .gateId(10L)
                .areaId(30L)
                .action(TrajectoryActionEnum.ENTER_AREA)
                .recordedAt(LocalDateTime.now().minusMinutes(150))
                .departureStatus(DepartureStatusEnum.IN_AREA)
                .build();

        when(trajectoryMapper.selectOvertimeVisitors(any(LocalDateTime.class), eq(120)))
                .thenReturn(Collections.singletonList(overtimeTraj));
        when(trajectoryMapper.updateById(any(VisitorTrajectory.class))).thenReturn(1);
        when(anomalyRecordMapper.insert(any(AnomalyRecord.class))).thenReturn(1);

        int count = trajectoryService.detectOvertimeStay();

        assertEquals(1, count);

        // Verify trajectory updated to OVERTIME
        assertEquals(DepartureStatusEnum.OVERTIME, overtimeTraj.getDepartureStatus());
        assertNotNull(overtimeTraj.getStayDurationMinutes());
        verify(trajectoryMapper).updateById(overtimeTraj);

        // Verify anomaly record created with OVERSTAY type
        ArgumentCaptor<AnomalyRecord> anomalyCaptor = ArgumentCaptor.forClass(AnomalyRecord.class);
        verify(anomalyRecordMapper).insert(anomalyCaptor.capture());
        AnomalyRecord anomaly = anomalyCaptor.getValue();
        assertEquals(5L, anomaly.getVisitorId());
        assertEquals(200L, anomaly.getAppointmentId());
        assertEquals(AnomalyTypeEnum.OVERSTAY, anomaly.getAnomalyType());
        assertEquals(AnomalyStatusEnum.OPEN, anomaly.getStatus());
        assertEquals(30L, anomaly.getAreaId());

        // Verify WebSocket push
        verify(webSocketPushService).pushOvertimeAreaStay(
                eq("visitor-5"), eq("area-30"), anyLong());
    }

    @Test
    void testDetectOvertimeStay_NoOvertime() {
        when(trajectoryMapper.selectOvertimeVisitors(any(LocalDateTime.class), eq(120)))
                .thenReturn(Collections.emptyList());

        int count = trajectoryService.detectOvertimeStay();

        assertEquals(0, count);
        verify(trajectoryMapper, never()).updateById(any());
        verify(anomalyRecordMapper, never()).insert(any());
        verify(webSocketPushService, never()).pushOvertimeAreaStay(any(), any(), anyLong());
    }

    @Test
    void testDetectOvertimeStay_IndividualErrorDoesntStop() {
        VisitorTrajectory traj1 = VisitorTrajectory.builder()
                .id(1L)
                .visitorId(5L)
                .appointmentId(200L)
                .gateId(10L)
                .areaId(30L)
                .action(TrajectoryActionEnum.ENTER_AREA)
                .recordedAt(LocalDateTime.now().minusMinutes(150))
                .departureStatus(DepartureStatusEnum.IN_AREA)
                .build();

        VisitorTrajectory traj2 = VisitorTrajectory.builder()
                .id(2L)
                .visitorId(6L)
                .appointmentId(201L)
                .gateId(11L)
                .areaId(31L)
                .action(TrajectoryActionEnum.ENTER_AREA)
                .recordedAt(LocalDateTime.now().minusMinutes(180))
                .departureStatus(DepartureStatusEnum.IN_AREA)
                .build();

        when(trajectoryMapper.selectOvertimeVisitors(any(LocalDateTime.class), eq(120)))
                .thenReturn(Arrays.asList(traj1, traj2));

        // First trajectory update throws, second succeeds
        when(trajectoryMapper.updateById(any(VisitorTrajectory.class)))
                .thenThrow(new RuntimeException("DB error"))
                .thenReturn(1);
        when(anomalyRecordMapper.insert(any(AnomalyRecord.class))).thenReturn(1);

        int count = trajectoryService.detectOvertimeStay();

        // Only the second trajectory should be counted
        assertEquals(1, count);

        // Verify the second trajectory was processed successfully
        verify(anomalyRecordMapper, times(1)).insert(any(AnomalyRecord.class));
        verify(webSocketPushService, times(1)).pushOvertimeAreaStay(
                eq("visitor-6"), eq("area-31"), anyLong());
    }

    @Test
    void testCheckCompanionConsistency_SeparatedCompanions() {
        VisitorTrajectory separated = VisitorTrajectory.builder()
                .id(1L)
                .visitorId(7L)
                .appointmentId(300L)
                .gateId(10L)
                .areaId(40L)
                .action(TrajectoryActionEnum.ENTER_AREA)
                .departureStatus(DepartureStatusEnum.IN_AREA)
                .build();

        when(trajectoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(separated));
        when(anomalyRecordMapper.insert(any(AnomalyRecord.class))).thenReturn(1);

        trajectoryService.checkCompanionConsistency(300L, 20L, "Zhang San");

        // Verify anomaly record created
        ArgumentCaptor<AnomalyRecord> anomalyCaptor = ArgumentCaptor.forClass(AnomalyRecord.class);
        verify(anomalyRecordMapper).insert(anomalyCaptor.capture());
        AnomalyRecord anomaly = anomalyCaptor.getValue();
        assertEquals(7L, anomaly.getVisitorId());
        assertEquals(300L, anomaly.getAppointmentId());
        assertEquals(AnomalyTypeEnum.DUPLICATE_ENTRY, anomaly.getAnomalyType());
        assertEquals(AnomalyStatusEnum.OPEN, anomaly.getStatus());
        assertEquals(20L, anomaly.getAreaId());
        assertTrue(anomaly.getDescription().contains("Zhang San"));

        // Verify WebSocket push
        verify(webSocketPushService).pushCompanionAnomaly(
                eq("Zhang San"), contains("20"));
    }
}
