package com.visitor.model.vo;

import com.visitor.model.enums.GateTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GateVO {
    private Long id;
    private String name;
    private Long areaId;
    private String areaName;
    private String building;
    private String locationDesc;
    private GateTypeEnum gateType;
    private Integer status;
    private LocalDateTime createdAt;
}
