package com.visitor.model.dto;

import com.visitor.model.enums.GateTypeEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GateCreateRequest {
    @NotBlank(message = "门岗名称不能为空")
    private String name;
    @NotNull(message = "区域ID不能为空")
    private Long areaId;
    private String locationDesc;
    private GateTypeEnum gateType;
}
