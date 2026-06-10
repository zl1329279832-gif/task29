package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AccessLogMapper;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.model.entity.Appointment;
import com.visitor.model.entity.Visitor;
import com.visitor.model.enums.AccessActionEnum;
import com.visitor.model.enums.AccessResultEnum;
import com.visitor.model.enums.AppointmentStatusEnum;
import com.visitor.model.vo.AccessLogVO;
import com.visitor.model.vo.TrajectoryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrajectoryServiceTest {

    @InjectMocks
    private TrajectoryService trajectoryService;

    @Mock private AccessLogMapper accessLogMapper;
    @Mock private AppointmentMapper appointmentMapper;
    @Mock private VisitorService visitorService;

    private Appointment testAppointment;
    private Visitor testVisitor;

    @BeforeEach
    void setUp() {
        testAppointment = Appointment.builder()
                .id(1L).appointNo("APT001").visitorId(10L)
                .status(AppointmentStatusEnum.CHECKED_IN).build();
        testVisitor = Visitor.builder()
                .id(10L).name("Li Si").build();
    }

    @Test
    void testGetTrajectory_SingleEntryExit() {
        LocalDateTime entry = LocalDateTime.now().minusHours(2);
        LocalDateTime exit = LocalDateTime.now().minusMinutes(30);

        AccessLogVO entryLog = AccessLogVO.builder()
                .id(1L).action(AccessActionEnum.ENTRY).result(AccessResultEnum.PASS)
                .gateId(1L).gateName("A栋正门").areaId(1L).areaName("A栋办公区")
                .createdAt(entry).build();
        AccessLogVO exitLog = AccessLogVO.builder()
                .id(2L).action(AccessActionEnum.EXIT).result(AccessResultEnum.PASS)
                .gateId(1L).gateName("A栋正门").areaId(1L).areaName("A栋办公区")
                .createdAt(exit).build();

        when(appointmentMapper.selectById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(accessLogMapper.selectTrajectory(1L)).thenReturn(List.of(entryLog, exitLog));

        TrajectoryVO result = trajectoryService.getTrajectory(1L);

        assertNotNull(result);
        assertEquals(1L, result.getAppointmentId());
        assertEquals("APT001", result.getAppointmentNo());
        assertEquals("Li Si", result.getVisitorName());
        assertEquals(entry, result.getEntryTime());
        assertEquals(exit, result.getExitTime());
        assertTrue(result.getDeparted());
        assertEquals(2, result.getPoints().size());
    }

    @Test
    void testGetTrajectory_MultiGate() {
        LocalDateTime t1 = LocalDateTime.now().minusHours(3);
        LocalDateTime t2 = LocalDateTime.now().minusHours(2);
        LocalDateTime t3 = LocalDateTime.now().minusHours(1);

        AccessLogVO log1 = AccessLogVO.builder()
                .id(1L).action(AccessActionEnum.ENTRY).result(AccessResultEnum.PASS)
                .gateId(1L).gateName("A栋正门").areaId(1L).areaName("A栋办公区")
                .createdAt(t1).build();
        AccessLogVO log2 = AccessLogVO.builder()
                .id(2L).action(AccessActionEnum.ENTRY).result(AccessResultEnum.PASS)
                .gateId(2L).gateName("B栋正门").areaId(2L).areaName("B栋会议区")
                .createdAt(t2).build();
        AccessLogVO log3 = AccessLogVO.builder()
                .id(3L).action(AccessActionEnum.EXIT).result(AccessResultEnum.PASS)
                .gateId(2L).gateName("B栋正门").areaId(2L).areaName("B栋会议区")
                .createdAt(t3).build();

        when(appointmentMapper.selectById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(accessLogMapper.selectTrajectory(1L)).thenReturn(List.of(log1, log2, log3));

        TrajectoryVO result = trajectoryService.getTrajectory(1L);

        assertEquals(3, result.getPoints().size());
        assertEquals(t1, result.getEntryTime());
        assertEquals(t3, result.getExitTime());
        assertTrue(result.getDeparted());
        // Current area is the area of the last ENTRY (B栋)
        assertEquals(2L, result.getCurrentAreaId());
        assertEquals("B栋会议区", result.getCurrentAreaName());
    }

    @Test
    void testGetTrajectory_NoExit_NotDeparted() {
        LocalDateTime entry = LocalDateTime.now().minusHours(1);

        AccessLogVO entryLog = AccessLogVO.builder()
                .id(1L).action(AccessActionEnum.ENTRY).result(AccessResultEnum.PASS)
                .gateId(1L).gateName("A栋正门").areaId(1L).areaName("A栋办公区")
                .createdAt(entry).build();

        when(appointmentMapper.selectById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(accessLogMapper.selectTrajectory(1L)).thenReturn(List.of(entryLog));

        TrajectoryVO result = trajectoryService.getTrajectory(1L);

        assertNotNull(result);
        assertEquals(entry, result.getEntryTime());
        assertNull(result.getExitTime());
        assertFalse(result.getDeparted());
        assertEquals(1L, result.getCurrentAreaId());
        assertEquals("A栋办公区", result.getCurrentAreaName());
    }

    @Test
    void testGetTrajectory_EmptyLogs() {
        when(appointmentMapper.selectById(1L)).thenReturn(testAppointment);
        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(accessLogMapper.selectTrajectory(1L)).thenReturn(Collections.emptyList());

        TrajectoryVO result = trajectoryService.getTrajectory(1L);

        assertNotNull(result);
        assertNull(result.getEntryTime());
        assertNull(result.getExitTime());
        assertFalse(result.getDeparted());
        assertTrue(result.getPoints().isEmpty());
    }

    @Test
    void testGetTrajectory_AppointmentNotFound() {
        when(appointmentMapper.selectById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> trajectoryService.getTrajectory(999L));
        assertEquals(ErrorCode.APPOINTMENT_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void testGetVisitorTrajectory_Pagination() {
        Appointment appt1 = Appointment.builder()
                .id(1L).appointNo("APT001").visitorId(10L).build();
        Appointment appt2 = Appointment.builder()
                .id(2L).appointNo("APT002").visitorId(10L).build();

        when(visitorService.getById(10L)).thenReturn(testVisitor);
        when(appointmentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(appt1, appt2));
        when(appointmentMapper.selectById(1L)).thenReturn(appt1);
        when(appointmentMapper.selectById(2L)).thenReturn(appt2);
        when(accessLogMapper.selectTrajectory(anyLong())).thenReturn(Collections.emptyList());

        Page<TrajectoryVO> result = trajectoryService.getVisitorTrajectory(10L, 1, 10);

        assertEquals(2, result.getTotal());
        assertEquals(2, result.getRecords().size());
    }
}
