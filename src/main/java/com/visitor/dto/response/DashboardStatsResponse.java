package com.visitor.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DashboardStatsResponse {
    private long todayAppointments;
    private long todayCheckIns;
    private long currentVisitors;
    private long pendingApprovals;
    private long todayAbnormalPasses;
    private long blacklistCount;
}
