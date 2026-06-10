package com.visitor.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AbnormalPassRequest {
    private String visitorName;
    private String visitorPhone;
    private String visitorIdCard;
    private String gateId;
    @NotBlank(message = "异常原因不能为空")
    private String reason;
    @NotBlank(message = "异常类型不能为空")
    private String type;
}
