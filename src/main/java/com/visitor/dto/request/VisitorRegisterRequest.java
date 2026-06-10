package com.visitor.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VisitorRegisterRequest {
    @NotBlank(message = "访客姓名不能为空")
    private String name;
    @NotBlank(message = "手机号不能为空")
    private String phone;
    private String idCard;
    private String company;
    private String email;
    private String photoUrl;
}
