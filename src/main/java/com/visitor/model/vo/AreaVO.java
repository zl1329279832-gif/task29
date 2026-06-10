package com.visitor.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AreaVO {
    private Long id;
    private String areaCode;
    private String areaName;
    private String buildingName;
    private String floorInfo;
    private String securityLevel;
    private Long parentAreaId;
    private String parentAreaName;
    private String status;
    private LocalDateTime createdAt;
}
