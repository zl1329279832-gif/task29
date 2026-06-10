package com.visitor.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VisitorRegisterRequest {
    @NotBlank(message = "访客姓名不能为空")
    private String name;
    private String idCard;
    private String phone;
    private String company;
    private String photoUrl;
}
