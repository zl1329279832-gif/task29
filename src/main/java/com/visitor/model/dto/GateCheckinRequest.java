package com.visitor.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GateCheckinRequest {
    @NotBlank(message = "通行码不能为空")
    private String passCode;
    private String gateLocation;
}
