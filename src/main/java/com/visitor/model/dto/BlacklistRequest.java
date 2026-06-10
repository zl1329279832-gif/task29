package com.visitor.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BlacklistRequest {
    @NotBlank(message = "姓名不能为空")
    private String name;
    private String idCard;
    private String phone;
    private String reason;
    private LocalDateTime expireAt;
}
