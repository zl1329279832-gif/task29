package com.visitor.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CheckInRequest {
    @NotBlank(message = "通行码不能为空")
    private String passCode;
    private String gateId;
}
