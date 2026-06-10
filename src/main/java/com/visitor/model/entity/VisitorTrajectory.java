package com.visitor.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.visitor.model.enums.DepartureStatusEnum;
import com.visitor.model.enums.TrajectoryActionEnum;
import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("visitor_trajectory")
public class VisitorTrajectory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long visitorId;
    private Long appointmentId;
    private Long gateId;
    private Long areaId;
    private TrajectoryActionEnum action;
    private LocalDateTime recordedAt;
    private Integer stayDurationMinutes;
    private DepartureStatusEnum departureStatus;
    private LocalDateTime createdAt;
}
