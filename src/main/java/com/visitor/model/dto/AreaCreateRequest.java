package com.visitor.model.dto;

import com.visitor.model.enums.SecurityLevelEnum;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AreaCreateRequest {
    @NotBlank(message = "区域编码不能为空")
    private String areaCode;
    @NotBlank(message = "区域名称不能为空")
    private String areaName;
    private String buildingName;
    private String floorInfo;
    private SecurityLevelEnum securityLevel;
    private Long parentAreaId;
}
