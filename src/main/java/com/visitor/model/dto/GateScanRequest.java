package com.visitor.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GateScanRequest {
    @NotBlank(message = "通行码不能为空")
    private String passCode;
    private Long gateId;
    private String gateLocation;
}
