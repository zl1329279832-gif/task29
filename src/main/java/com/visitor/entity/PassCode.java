package com.visitor.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class PassCode {
    private Long id;
    private Long appointmentId;
    private String code;
    private String status;
    private Integer maxUseCount;
    private Integer usedCount;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
