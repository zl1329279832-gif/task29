package com.visitor.model.dto;

import com.visitor.model.enums.AnomalyTypeEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AnomalyReleaseRequest {
    @NotNull(message = "访客ID不能为空")
    private Long visitorId;
    private Long appointmentId;
    @NotNull(message = "异常类型不能为空")
    private AnomalyTypeEnum anomalyType;
    private String description;
    private String gateLocation;
    private Long gateId;
}
