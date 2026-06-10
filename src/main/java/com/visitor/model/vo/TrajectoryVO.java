package com.visitor.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrajectoryVO {
    private Long appointmentId;
    private String appointmentNo;
    private Long visitorId;
    private String visitorName;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private Boolean departed;
    private Long currentAreaId;
    private String currentAreaName;
    private List<TrajectoryPointVO> points;
}
