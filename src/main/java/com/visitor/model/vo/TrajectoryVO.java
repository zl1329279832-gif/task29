package com.visitor.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrajectoryVO {
    private Long id;
    private Long visitorId;
    private Long appointmentId;
    private Long gateId;
    private Long areaId;
    private String action;
    private LocalDateTime recordedAt;
    private Integer stayDurationMinutes;
    private String departureStatus;
    private String visitorName;
    private String gateName;
    private String areaName;
}
