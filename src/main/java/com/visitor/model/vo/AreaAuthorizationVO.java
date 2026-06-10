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
public class AreaAuthorizationVO {
    private Long id;
    private Long appointmentId;
    private Long visitorId;
    private String visitorName;
    private Long areaId;
    private String areaName;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private String status;
    private LocalDateTime createdAt;
}
