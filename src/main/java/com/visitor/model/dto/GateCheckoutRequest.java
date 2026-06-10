package com.visitor.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GateCheckoutRequest {
    @NotNull(message = "访客ID不能为空")
    private Long visitorId;
    @NotNull(message = "预约ID不能为空")
    private Long appointmentId;
    private String gateLocation;
}
