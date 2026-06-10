package com.visitor.controller;

import com.visitor.common.result.Result;
import com.visitor.dto.response.DashboardStatsResponse;
import com.visitor.mapper.AbnormalPassRecordMapper;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.BlacklistMapper;
import com.visitor.mapper.CheckInRecordMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final AppointmentMapper appointmentMapper;
    private final CheckInRecordMapper checkInRecordMapper;
    private final AbnormalPassRecordMapper abnormalPassRecordMapper;
    private final BlacklistMapper blacklistMapper;

    public DashboardController(AppointmentMapper appointmentMapper, CheckInRecordMapper checkInRecordMapper,
                                AbnormalPassRecordMapper abnormalPassRecordMapper, BlacklistMapper blacklistMapper) {
        this.appointmentMapper = appointmentMapper;
        this.checkInRecordMapper = checkInRecordMapper;
        this.abnormalPassRecordMapper = abnormalPassRecordMapper;
        this.blacklistMapper = blacklistMapper;
    }

    @GetMapping("/stats")
    public Result<DashboardStatsResponse> getStats() {
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);

        DashboardStatsResponse stats = DashboardStatsResponse.builder()
                .todayAppointments(appointmentMapper.countTodayAppointments(todayStart, todayEnd))
                .todayCheckIns(checkInRecordMapper.countTodayCheckIns(todayStart, todayEnd))
                .currentVisitors(checkInRecordMapper.countCurrentVisitors())
                .pendingApprovals(appointmentMapper.countPendingApprovals())
                .todayAbnormalPasses(abnormalPassRecordMapper.countToday(todayStart, todayEnd))
                .blacklistCount(blacklistMapper.countActive())
                .build();

        return Result.ok(stats);
    }
}
