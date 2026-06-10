package com.visitor.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AreaAuthorizationRequest {
    @NotNull(message = "预约ID不能为空")
    private Long appointmentId;
    @NotNull(message = "区域ID不能为空")
    private Long areaId;
    @NotNull(message = "生效时间不能为空")
    private LocalDateTime validFrom;
    @NotNull(message = "失效时间不能为空")
    private LocalDateTime validTo;
}
