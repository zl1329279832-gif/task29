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
import com.visitor.model.vo.AccessLogVO;
import com.visitor.model.vo.TrajectoryPointVO;
import com.visitor.model.vo.TrajectoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrajectoryService {

    private final AccessLogMapper accessLogMapper;
    private final AppointmentMapper appointmentMapper;
    private final VisitorService visitorService;

    /**
     * Get trajectory for a specific appointment.
     * Computed from access_log entries ordered by time.
     */
    public TrajectoryVO getTrajectory(Long appointmentId) {
        Appointment appointment = appointmentMapper.selectById(appointmentId);
        if (appointment == null) {
            throw new BizException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }

        Visitor visitor = visitorService.getById(appointment.getVisitorId());
        List<AccessLogVO> logs = accessLogMapper.selectTrajectory(appointmentId);

        LocalDateTime entryTime = null;
        LocalDateTime exitTime = null;
        Long currentAreaId = null;
        String currentAreaName = null;
        List<TrajectoryPointVO> points = new ArrayList<>();
        // Track the last PASS action to determine current state:
        // last ENTRY → visitor is in that area; last EXIT → visitor has departed
        AccessActionEnum lastPassAction = null;

        for (AccessLogVO logEntry : logs) {
            TrajectoryPointVO point = TrajectoryPointVO.builder()
                    .accessLogId(logEntry.getId())
                    .action(logEntry.getAction())
                    .gateId(logEntry.getGateId())
                    .gateName(logEntry.getGateName())
                    .areaId(logEntry.getAreaId())
                    .areaName(logEntry.getAreaName())
                    .result(logEntry.getResult())
                    .timestamp(logEntry.getCreatedAt())
                    .build();
            points.add(point);

            if (logEntry.getResult() != AccessResultEnum.PASS) {
                continue;
            }

            if (logEntry.getAction() == AccessActionEnum.ENTRY) {
                if (entryTime == null) {
                    entryTime = logEntry.getCreatedAt();
                }
                currentAreaId = logEntry.getAreaId();
                currentAreaName = logEntry.getAreaName();
                lastPassAction = AccessActionEnum.ENTRY;
            }
            if (logEntry.getAction() == AccessActionEnum.EXIT) {
                exitTime = logEntry.getCreatedAt();
                // Clear current area on exit — trajectory closure
                currentAreaId = null;
                currentAreaName = null;
                lastPassAction = AccessActionEnum.EXIT;
            }
        }

        // departed = true when the last successful action is EXIT
        boolean departed = lastPassAction == AccessActionEnum.EXIT;

        return TrajectoryVO.builder()
                .appointmentId(appointmentId)
                .appointmentNo(appointment.getAppointNo())
                .visitorId(visitor.getId())
                .visitorName(visitor.getName())
                .entryTime(entryTime)
                .exitTime(exitTime)
                .departed(departed)
                .currentAreaId(currentAreaId)
                .currentAreaName(currentAreaName)
                .points(points)
                .build();
    }

    /**
     * Get all trajectories for a visitor across appointments.
     */
    public Page<TrajectoryVO> getVisitorTrajectory(Long visitorId, int page, int size) {
        visitorService.getById(visitorId); // validate exists

        List<Appointment> appointments = appointmentMapper.selectList(
                new LambdaQueryWrapper<Appointment>()
                        .eq(Appointment::getVisitorId, visitorId)
                        .orderByDesc(Appointment::getCreatedAt));

        Page<TrajectoryVO> result = new Page<>(page, size);
        int start = (page - 1) * size;
        int end = Math.min(start + size, appointments.size());

        List<TrajectoryVO> trajectories = new ArrayList<>();
        if (start < appointments.size()) {
            for (Appointment appt : appointments.subList(start, end)) {
                trajectories.add(getTrajectory(appt.getId()));
            }
        }
        result.setRecords(trajectories);
        result.setTotal(appointments.size());
        return result;
    }
}
