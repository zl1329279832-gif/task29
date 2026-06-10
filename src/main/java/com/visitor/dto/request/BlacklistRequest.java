package com.visitor.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BlacklistRequest {
    @NotBlank(message = "姓名不能为空")
    private String name;
    private String idCard;
    private String phone;
    @NotBlank(message = "拉黑原因不能为空")
    private String reason;
    private Long visitorId;
}
