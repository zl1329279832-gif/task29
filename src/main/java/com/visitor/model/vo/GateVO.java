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
public class GateVO {
    private Long id;
    private String gateCode;
    private String gateName;
    private Long areaId;
    private String areaName;
    private String direction;
    private String status;
    private LocalDateTime createdAt;
}
