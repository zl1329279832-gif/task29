package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.mapper.AnomalyRecordMapper;
import com.visitor.mapper.VisitorTrajectoryMapper;
import com.visitor.model.entity.AnomalyRecord;
import com.visitor.model.entity.VisitorTrajectory;
import com.visitor.model.enums.AnomalyStatusEnum;
import com.visitor.model.enums.AnomalyTypeEnum;
import com.visitor.model.enums.DepartureStatusEnum;
import com.visitor.model.enums.TrajectoryActionEnum;
import com.visitor.model.vo.TrajectoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrajectoryService {

    private final VisitorTrajectoryMapper trajectoryMapper;
    private final AnomalyRecordMapper anomalyRecordMapper;
    private final WebSocketPushService webSocketPushService;

    private static final int DEFAULT_OVERTIME_THRESHOLD_MINUTES = 120;

    /**
     * Record visitor entering an area through a gate.
     */
    public VisitorTrajectory recordEntry(Long visitorId, Long appointmentId, Long gateId, Long areaId) {
        VisitorTrajectory trajectory = VisitorTrajectory.builder()
                .visitorId(visitorId)
                .appointmentId(appointmentId)
                .gateId(gateId)
                .areaId(areaId)
                .action(TrajectoryActionEnum.ENTER_AREA)
                .recordedAt(LocalDateTime.now())
                .departureStatus(DepartureStatusEnum.IN_AREA)
                .build();
        trajectoryMapper.insert(trajectory);
        log.info("Recorded area entry: visitor={}, area={}, gate={}", visitorId, areaId, gateId);
        return trajectory;
    }

    /**
     * Record visitor exiting an area through a gate.
     * Finds the most recent ENTER_AREA record for the same visitor+area and calculates stay duration.
     */
    @Transactional
    public VisitorTrajectory recordExit(Long visitorId, Long appointmentId, Long gateId, Long areaId) {
        // Find the latest entry record for this visitor in this area
        LambdaQueryWrapper<VisitorTrajectory> entryWrapper = new LambdaQueryWrapper<>();
        entryWrapper.eq(VisitorTrajectory::getVisitorId, visitorId)
                .eq(VisitorTrajectory::getAreaId, areaId)
                .eq(VisitorTrajectory::getAction, TrajectoryActionEnum.ENTER_AREA)
                .eq(VisitorTrajectory::getDepartureStatus, DepartureStatusEnum.IN_AREA)
                .orderByDesc(VisitorTrajectory::getRecordedAt)
                .last("LIMIT 1");
        VisitorTrajectory entryRecord = trajectoryMapper.selectOne(entryWrapper);

        Integer stayDuration = null;
        if (entryRecord != null) {
            stayDuration = (int) Duration.between(entryRecord.getRecordedAt(), LocalDateTime.now()).toMinutes();
            entryRecord.setDepartureStatus(DepartureStatusEnum.DEPARTED);
            entryRecord.setStayDurationMinutes(stayDuration);
            trajectoryMapper.updateById(entryRecord);
        }

        VisitorTrajectory exitRecord = VisitorTrajectory.builder()
                .visitorId(visitorId)
                .appointmentId(appointmentId)
                .gateId(gateId)
                .areaId(areaId)
                .action(TrajectoryActionEnum.EXIT_AREA)
                .recordedAt(LocalDateTime.now())
                .stayDurationMinutes(stayDuration)
                .departureStatus(DepartureStatusEnum.DEPARTED)
                .build();
        trajectoryMapper.insert(exitRecord);
        log.info("Recorded area exit: visitor={}, area={}, stay={}min", visitorId, areaId, stayDuration);
        return exitRecord;
    }

    /**
     * Record visitor passing through an internal gate (not entry/exit).
     */
    public VisitorTrajectory recordPassThrough(Long visitorId, Long appointmentId, Long gateId, Long areaId) {
        VisitorTrajectory trajectory = VisitorTrajectory.builder()
                .visitorId(visitorId)
                .appointmentId(appointmentId)
                .gateId(gateId)
                .areaId(areaId)
                .action(TrajectoryActionEnum.PASS_GATE)
                .recordedAt(LocalDateTime.now())
                .departureStatus(DepartureStatusEnum.IN_AREA)
                .build();
        trajectoryMapper.insert(trajectory);
        log.info("Recorded gate pass-through: visitor={}, gate={}", visitorId, gateId);
        return trajectory;
    }

    /**
     * Get visitor trajectory.
     */
    public List<TrajectoryVO> getTrajectory(Long visitorId, Long appointmentId) {
        return trajectoryMapper.selectTrajectory(visitorId, appointmentId);
    }

    /**
     * Get paginated trajectory.
     */
    public Page<TrajectoryVO> getTrajectoryPaged(Long visitorId, Long appointmentId, int page, int size) {
        List<TrajectoryVO> all = getTrajectory(visitorId, appointmentId);
        Page<TrajectoryVO> result = new Page<>(page, size);
        int start = (page - 1) * size;
        int end = Math.min(start + size, all.size());
        if (start < all.size()) {
            result.setRecords(all.subList(start, end));
        }
        result.setTotal(all.size());
        return result;
    }

    /**
     * Get visitors currently in a specific area.
     */
    public List<VisitorTrajectory> getCurrentAreaVisitors(Long areaId) {
        return trajectoryMapper.selectCurrentAreaVisitors(areaId);
    }

    /**
     * Detect visitors who have stayed in an area too long.
     * Creates anomaly records and pushes to security.
     */
    @Transactional
    public int detectOvertimeStay() {
        List<VisitorTrajectory> overtime = trajectoryMapper.selectOvertimeVisitors(
                LocalDateTime.now(), DEFAULT_OVERTIME_THRESHOLD_MINUTES);

        int count = 0;
        for (VisitorTrajectory traj : overtime) {
            try {
                int stayMinutes = (int) Duration.between(traj.getRecordedAt(), LocalDateTime.now()).toMinutes();

                // Update trajectory status
                traj.setDepartureStatus(DepartureStatusEnum.OVERTIME);
                traj.setStayDurationMinutes(stayMinutes);
                trajectoryMapper.updateById(traj);

                // Create anomaly record
                AnomalyRecord anomaly = AnomalyRecord.builder()
                        .visitorId(traj.getVisitorId())
                        .appointmentId(traj.getAppointmentId())
                        .anomalyType(AnomalyTypeEnum.OVERSTAY)
                        .description("访客在区域停留超过 " + stayMinutes + " 分钟")
                        .gateId(traj.getGateId())
                        .areaId(traj.getAreaId())
                        .status(AnomalyStatusEnum.OPEN)
                        .build();
                anomalyRecordMapper.insert(anomaly);

                webSocketPushService.pushOvertimeAreaStay(
                        "visitor-" + traj.getVisitorId(),
                        "area-" + traj.getAreaId(),
                        stayMinutes);

                count++;
            } catch (Exception e) {
                log.error("Error processing overtime trajectory {}: {}", traj.getId(), e.getMessage());
            }
        }
        if (count > 0) {
            log.info("Detected {} overtime area stays", count);
        }
        return count;
    }

    /**
     * Check companion consistency - verify visitors from the same appointment
     * are in the same area. If not, generate anomaly.
     */
    public void checkCompanionConsistency(Long appointmentId, Long expectedAreaId, String visitorName) {
        LambdaQueryWrapper<VisitorTrajectory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(VisitorTrajectory::getAppointmentId, appointmentId)
                .eq(VisitorTrajectory::getAction, TrajectoryActionEnum.ENTER_AREA)
                .eq(VisitorTrajectory::getDepartureStatus, DepartureStatusEnum.IN_AREA)
                .ne(VisitorTrajectory::getAreaId, expectedAreaId);
        List<VisitorTrajectory> separated = trajectoryMapper.selectList(wrapper);

        if (!separated.isEmpty()) {
            AnomalyRecord anomaly = AnomalyRecord.builder()
                    .visitorId(separated.get(0).getVisitorId())
                    .appointmentId(appointmentId)
                    .anomalyType(AnomalyTypeEnum.DUPLICATE_ENTRY)
                    .description("同行人异常：访客 " + visitorName + " 与同行人不在同一区域")
                    .areaId(expectedAreaId)
                    .status(AnomalyStatusEnum.OPEN)
                    .build();
            anomalyRecordMapper.insert(anomaly);

            webSocketPushService.pushCompanionAnomaly(visitorName,
                    "同行人不在同一区域，当前区域ID: " + expectedAreaId);
            log.warn("Companion anomaly detected for appointment {}", appointmentId);
        }
    }
}
