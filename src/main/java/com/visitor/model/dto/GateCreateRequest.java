package com.visitor.model.dto;

import com.visitor.model.enums.GateDirectionEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GateCreateRequest {
    @NotBlank(message = "门岗编码不能为空")
    private String gateCode;
    @NotBlank(message = "门岗名称不能为空")
    private String gateName;
    @NotNull(message = "区域ID不能为空")
    private Long areaId;
    private GateDirectionEnum direction;
}
