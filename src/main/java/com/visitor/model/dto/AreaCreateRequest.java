package com.visitor.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AreaCreateRequest {
    @NotBlank(message = "区域名称不能为空")
    private String name;
    private String building;
    private String description;
}
